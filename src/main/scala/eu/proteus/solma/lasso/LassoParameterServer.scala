/*
 * Copyright (C) 2017 The Proteus Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.proteus.solma.lasso

import breeze.linalg._
import hu.sztaki.ilab.ps.entities.{PSToWorker, Pull, Push, WorkerToPS}
import hu.sztaki.ilab.ps.server.{RangePSLogicWithClose, SimplePSLogicWithClose}
import hu.sztaki.ilab.ps.{FlinkParameterServer, ParameterServerClient, WorkerLogic}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.scala._
import eu.proteus.solma.lasso.algorithm.LassoAlgorithm
import eu.proteus.solma.lasso.Lasso.{LassoModel, LassoParam, OptionLabeledVector}
import eu.proteus.solma.lasso.LassoStreamEvent.LassoStreamEvent
import eu.proteus.solma.lasso.algorithm.LassoParameterInitializer._

class LassoParameterServer


object LassoParameterServer {

  /**
    * Applies online Lasso algorithm for a [[org.apache.flink.streaming.api.scala.DataStream]] of vectors.
    * For vectors with labels, it updates the Lasso model,
    * for vectors without label predicts its labels based on the model.
    *
    * Note that the order could be mixed, i.e. it's possible to predict based on some model parameters updated, while
    * others not.
    *
    * @param inputSource
    * [[org.apache.flink.streaming.api.scala.DataStream]] of labelled and unlabelled vector. The label is marked with an
    * [[Option]].
    * @param workerParallelism
    * Number of worker instances for Parameter Server.
    * @param psParallelism
    * Number of Parameter Server instances.
    * @param lassoMethod
    * Method for Lasso training.
    * @param pullLimit
    * Limit of unanswered pulls at a worker instance.
    * @param iterationWaitTime
    * Time to wait for new messages at worker. If set to 0, the job will run infinitely.
    * PS is implemented with a Flink iteration, and Flink does not know when the iteration finishes,
    * so this is how the job can finish.
    * @return
    * Stream of Parameter Server model updates and predicted values.
    */
  // scalastyle:off parameter.number
  def transformLasso(model: Option[DataStream[(Int, LassoModel)]] = None)
                    (inputSource: DataStream[LassoStreamEvent],
                     lassoWorkerLogic: WorkerLogic[LassoStreamEvent, LassoParam, ((Long, Double), Double)],
                     workerParallelism: Int,
                     psParallelism: Int,
                     lassoMethod: LassoAlgorithm[OptionLabeledVector, LassoParam, ((Long, Double), Double), LassoModel],
                     pullLimit: Int,
                     featureCount: Int,
                     rangePartitioning: Boolean,
                     iterationWaitTime: Long): DataStream[Either[((Long, Double), Double), (Int, LassoParam)]] = {
    val labelCount = 1

    val concreteModelBuilder = new LassoModelBuilder(initConcrete(1.0, 0.0, featureCount)(0))

    transformGeneric[LassoParam, ((Long, Double), Double), LassoModel, OptionLabeledVector, LassoStreamEvent,
      Int](model)(initConcrete(1.0, 0.0, featureCount), concreteModelBuilder.addParams, concreteModelBuilder
    )(
      inputSource, lassoWorkerLogic, workerParallelism, psParallelism, lassoMethod,
      pullLimit, labelCount, featureCount, rangePartitioning, iterationWaitTime
    )
  }
  // scalastyle:on parameter.number

  // scalastyle:off parameter.number
  // scalastyle:off method.length
  private def transformGeneric
  [Param, Label, Model, Vec, Event, VecId](model: Option[DataStream[(Int, Param)]] = None)
                                   (init: Int => Param,
                                    add: (Param, Param) => Param,
                                    modelBuilder: ModelBuilder[Param, Model])
                                   (inputSource: DataStream[Event],
                                    lassoWorkerLogic: WorkerLogic[Event, Param, Label],
                                    workerParallelism: Int,
                                    psParallelism: Int,
                                    lassoMethod: LassoAlgorithm[Vec, Param, Label, Model],
                                    pullLimit: Int,
                                    labelCount: Int,
                                    featureCount: Int,
                                    rangePartitioning: Boolean,
                                    iterationWaitTime: Long)
                                   (implicit
                                    tiParam: TypeInformation[Param],
                                    tiLabel: TypeInformation[Label],
                                    tiVec: TypeInformation[Vec],
                                    tiEvent: TypeInformation[Event],
                                    tiVecId: TypeInformation[VecId]/*,
                                    ev: OptionLabeledVectorWithId[Vec, VecId, Label]*/)
  : DataStream[Either[Label, (Int, Param)]] = {
    val serverLogic =
      if (rangePartitioning) {
        new RangePSLogicWithClose[Param](featureCount, init, add)
      } else {
        new SimplePSLogicWithClose[Param](init, add)
      }

    val paramPartitioner: WorkerToPS[Param] => Int =
      if (rangePartitioning) {
        rangePartitionerPS(featureCount)(psParallelism)
      } else {
        val partitonerFunction = (paramId: Int) => Math.abs(paramId) % psParallelism
        val p: WorkerToPS[Param] => Int = {
          case WorkerToPS(_, msg) => msg match {
            case Left(Pull(paramId)) => partitonerFunction(paramId)
            case Right(Push(paramId, _)) => partitonerFunction(paramId)
          }
        }
        p
      }

    val workerLogic = WorkerLogic.addPullLimiter(lassoWorkerLogic, pullLimit)


    val wInPartition: PSToWorker[Param] => Int = {
      case PSToWorker(workerPartitionIndex, _) => workerPartitionIndex
    }

    val modelUpdates = model match {
      case Some(m) => FlinkParameterServer.transformWithModelLoad(m)(
        inputSource, workerLogic, serverLogic,
        paramPartitioner,
        wInPartition,
        workerParallelism,
        psParallelism,
        iterationWaitTime)
      case None =>
        FlinkParameterServer.transform(inputSource, workerLogic, serverLogic, workerParallelism, psParallelism,
          iterationWaitTime)
    }
    modelUpdates
  }
  // scalastyle:on method.length
  // scalastyle:on parameter.number

  def rangePartitionerPS[P](featureCount: Int)(psParallelism: Int): (WorkerToPS[P]) => Int = {
    val partitionSize = Math.ceil(featureCount.toDouble / psParallelism).toInt
    val partitonerFunction = (paramId: Int) => Math.abs(paramId) / partitionSize

    val paramPartitioner: WorkerToPS[P] => Int = {
      case WorkerToPS(_, msg) => msg match {
        case Left(Pull(paramId)) => partitonerFunction(paramId)
        case Right(Push(paramId, _)) => partitonerFunction(paramId)
      }
    }

    paramPartitioner
  }

}
