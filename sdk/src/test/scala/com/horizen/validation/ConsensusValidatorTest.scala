package com.horizen.validation

import java.math.BigInteger
import java.time.Instant
import java.util.Random

import com.horizen.SidechainHistory
import com.horizen.block.SidechainBlock
import com.horizen.consensus.{FullConsensusEpochInfo, HistoryConsensusChecker}
import com.horizen.fixtures.VrfGenerator
import com.horizen.fixtures.sidechainblock.generation.{ForgerBoxCorruptionRules, GenerationRules, SidechainBlocksGenerator}
import com.horizen.params.TestNetParams
import org.junit.Test
import org.scalatest.junit.JUnitSuite

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class ConsensusValidatorTest extends JUnitSuite with HistoryConsensusChecker {
  var rnd = new Random(42)
  val maximumAvailableShift = 2

  private def createHistoryWithBlocksNoForksAndPossibleNextForger(epochSizeInSlots: Int, slotLengthInSeconds: Int, totalBlocksCount: Int, blocksInHistoryCount: Int):
  (SidechainHistory, mutable.Buffer[SidechainBlocksGenerator], mutable.Buffer[SidechainBlock]) = {
    var res: Option[(SidechainHistory, mutable.Buffer[SidechainBlocksGenerator], mutable.Buffer[SidechainBlock])] =  None
    var iteration = 0
    while (res.isEmpty) {
      val resTry = Try(createHistoryWithBlocksNoForks(new Random(rnd.nextLong()), epochSizeInSlots, slotLengthInSeconds, totalBlocksCount, blocksInHistoryCount))
      resTry match {
        case (Success(_)) => res = resTry.toOption
        case Failure(exception) => {
          println(exception.printStackTrace())
          iteration = iteration + 1
        }
      }

      if (resTry.isFailure) println(resTry.failed.get.printStackTrace())
      res = resTry.toOption

      require(iteration < 500, "Cannot generate blocks chain for test, block generation is broken")
    }
    (res.get._1, res.get._2, res.get._3)
  }

  private def createHistoryWithBlocksNoForks(rnd: Random, epochSizeInSlots: Int, slotLengthInSeconds: Int, totalBlockCount: Int, blocksInHistoryCount: Int):
    (SidechainHistory, mutable.Buffer[SidechainBlocksGenerator], mutable.Buffer[SidechainBlock]) = {
    val genesisTimestamp: Int = 1583987714

    val initialParams = TestNetParams(
      consensusSlotsInEpoch = epochSizeInSlots,
      consensusSecondsInSlot = slotLengthInSeconds,
      sidechainGenesisBlockTimestamp = genesisTimestamp)

    val (params, genesisBlock, genesisGenerator, genesisForgingData, genesisEndEpochInfo) = SidechainBlocksGenerator.startSidechain(10000000000L, rnd.nextInt(), initialParams)

    var history: SidechainHistory = createHistory(params, genesisBlock, genesisEndEpochInfo)
    history.applyFullConsensusInfo(genesisBlock.id, FullConsensusEpochInfo(genesisEndEpochInfo.stakeConsensusEpochInfo, genesisEndEpochInfo.nonceConsensusEpochInfo))
    println(s"//////////////// Genesis epoch ${genesisBlock.id} had been ended ////////////////")

    var lastGenerator: SidechainBlocksGenerator = genesisGenerator
    val generators = mutable.Buffer(genesisGenerator)
    val generatedBlocks = mutable.Buffer(genesisBlock)

    for (i <- 1 to totalBlockCount) {
      val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, lastGenerator.getNotSpentBoxes)

      val (gens, generatedBlock) = generateBlock(generationRules, lastGenerator, history)
      if (i < blocksInHistoryCount) {
        history = historyUpdateShallBeSuccessful(history, generatedBlock)
        generatedBlocks.append(generatedBlock)
        generators.appendAll(gens)
        println(s"Generate normal block ${generatedBlock.id}")
      }
      else {
        println(s"Generate extra block ${generatedBlock.id}")
      }

      lastGenerator = gens.last
    }

    (history, generators, generatedBlocks)
  }

  @Test
  def nonGenesisBlockCheck(): Unit = {
    val epochSizeInSlots = 10
    val slotLengthInSeconds = 20
    val totalBlocks = epochSizeInSlots * 4
    val (history: SidechainHistory, generators: Seq[SidechainBlocksGenerator], blocks) = createHistoryWithBlocksNoForksAndPossibleNextForger(epochSizeInSlots, slotLengthInSeconds, totalBlocks, totalBlocks - maximumAvailableShift)

    val lastGenerator = generators.last

    /////////// Timestamp related checks //////////////
    //check block in the future
    println("Test blockInFuture")
    /* TODO: for some reason generateBlockInTheFuture return block with invalid VRF. Maybe, the fix in ouroboros_forger_impl will solve the problem.
    val blockInFuture = generateBlockInTheFuture(lastGenerator)
    history.append(blockInFuture).failed.get match {
      case expected: IllegalArgumentException => assert(expected.getMessage == "Block had been generated in the future")
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }

    println("Test blockGeneratedBeforeParent")
    val blockGeneratedBeforeParent = generateBlockWithTimestampBeforeParent(lastGenerator, blocks.last.timestamp)
    history.append(blockGeneratedBeforeParent).failed.get match {
      case expected: IllegalArgumentException => assert(expected.getMessage == "Block had been generated before parent block had been generated")
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }

    println("Test blockWithTheSameSlotAsParent")
    val blockWithTheSameSlotAsParent = generateBlockForTheSameSlot(generators)
    history.append(blockWithTheSameSlotAsParent).failed.get match {
      case expected: IllegalArgumentException => assert(expected.getMessage == "Block absolute slot number is equal or less than parent block")
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }
    //
    println("Test blockGeneratedWithSkippedEpoch")

    val blockGeneratedWithSkippedEpoch = generateBlockWithSkippedEpoch(lastGenerator, blocks.last.timestamp, slotLengthInSeconds * epochSizeInSlots)
    history.append(blockGeneratedWithSkippedEpoch).failed.get match {
      case expected: IllegalStateException => assert(expected.getMessage == "Whole epoch had been skipped")
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }*/



    /////////// VRF verification /////////////////
    println("Test blockGeneratedWithIncorrectNonce")
    val blockGeneratedWithIncorrectNonce = generateBlockWithIncorrectNonce(lastGenerator)
    history.append(blockGeneratedWithIncorrectNonce).failed.get match {
      case expected: IllegalStateException => assert(expected.getMessage == s"VRF check for block ${blockGeneratedWithIncorrectNonce.id} had been failed")
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }

    println("Test blockGeneratedWithIncorrectSlot")
    val blockGeneratedWithIncorrectSlot = generateBlockWithIncorrectSlot(lastGenerator)
    history.append(blockGeneratedWithIncorrectSlot).failed.get match {
      case expected: IllegalStateException => assert(expected.getMessage == s"VRF check for block ${blockGeneratedWithIncorrectSlot.id} had been failed")
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }

    println("Test blockGeneratedWithIncorrectVrfPublicKey")
    val blockGeneratedWithIncorrectVrfPublicKey = generateBlockWithIncorrectVrfPublicKey(lastGenerator)
    history.append(blockGeneratedWithIncorrectVrfPublicKey).failed.get match {
      case expected: IllegalStateException => assert(expected.getMessage == s"VRF check for block ${blockGeneratedWithIncorrectVrfPublicKey.id} had been failed")
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }

    println("Test blockGeneratedWithIncorrectVrfProof")
    val blockGeneratedWithIncorrectVrfProof = generateBlockWithIncorrectVrfProof(lastGenerator)
    history.append(blockGeneratedWithIncorrectVrfProof).failed.get match {
      case expected: IllegalStateException => assert(expected.getMessage == s"VRF check for block ${blockGeneratedWithIncorrectVrfProof.id} had been failed")
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }


    /////////// Forger box verification /////////////////
    println("Test blockGeneratedWithIncorrectForgerBoxRewardProposition")
    val blockGeneratedWithIncorrectForgerBoxRewardProposition = generateBlockWithIncorrectForgerBoxRewardProposition(lastGenerator)
    history.append(blockGeneratedWithIncorrectForgerBoxRewardProposition).failed.get match {
      case expected: IllegalStateException => assert(expected.getMessage.contains(s"Forger box merkle path in block ${blockGeneratedWithIncorrectForgerBoxRewardProposition.id} is inconsistent to stakes merkle root hash"))
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }

    println("Test blockGeneratedWithIncorrectForgerBoxProposition")
    val blockGeneratedWithIncorrectForgerBoxProposition = generateBlockWithIncorrectForgerBoxProposition(lastGenerator)
    history.append(blockGeneratedWithIncorrectForgerBoxProposition).failed.get match {
      case expected: IllegalStateException => assert(expected.getMessage.contains(s"Forger box merkle path in block ${blockGeneratedWithIncorrectForgerBoxProposition.id} is inconsistent to stakes merkle root hash"))
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }

    println("Test blockGeneratedWithIncorrectForgerNonce")
    val blockGeneratedWithIncorrectForgerNonce = generateBlockWithIncorrectForgerBoxNonce(lastGenerator)
    history.append(blockGeneratedWithIncorrectForgerNonce).failed.get match {
      case expected: IllegalStateException => assert(expected.getMessage.contains(s"Forger box merkle path in block ${blockGeneratedWithIncorrectForgerNonce.id} is inconsistent to stakes merkle root hash"))
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }

    println("Test blockGeneratedWithIncorrectValue")
    val blockGeneratedWithIncorrectValue = generateBlockWithIncorrectForgerBoxValue(lastGenerator)
    history.append(blockGeneratedWithIncorrectValue).failed.get match {
      case expected: IllegalStateException => assert(expected.getMessage.contains(s"Forger box merkle path in block ${blockGeneratedWithIncorrectValue.id} is inconsistent to stakes merkle root hash"))
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }

    /////////// Stake verification /////////////////
    println("Test blockWithNotEnoughStake")
    val blockWithNotEnoughStake = generateBlockWithNotEnoughStake(lastGenerator)
    history.append(blockWithNotEnoughStake).failed.get match {
      case expected: IllegalArgumentException => assert(expected.getMessage == s"Stake value in forger box in block ${blockWithNotEnoughStake.id} is not enough for to be forger.")
      case nonExpected => assert(false, s"Got incorrect exception: ${nonExpected}")
    }
  }

  // TODO: this corruption doesn't work anymore, because vrf is verified before timestamp now.
  def generateBlockInTheFuture(generator: SidechainBlocksGenerator): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes).copy(forcedTimestamp = Some(Instant.now.getEpochSecond + 1000))
    generateBlock(generationRules, generator)._2
  }

  def generateBlockWithTimestampBeforeParent(generator: SidechainBlocksGenerator, previousBlockTimestamp: Long): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes).copy(forcedTimestamp = Some(previousBlockTimestamp - 1))
    generateBlock(generationRules, generator)._2
  }

  def generateBlockForTheSameSlot(generators: Seq[SidechainBlocksGenerator]): SidechainBlock = {
    val preLastGenerator = generators(generators.size - 2) //get prelast
    val bestBlockId = generators.last.lastBlockId
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, preLastGenerator.getNotSpentBoxes).copy(forcedParentId = Some(bestBlockId))
    generateBlock(generationRules, preLastGenerator)._2
  }

  def generateBlockWithSkippedEpoch(generator: SidechainBlocksGenerator, previousBlockTimestamp: Long, epochLengthInSeconds: Long): SidechainBlock = {
    val generationRules =
      GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes).copy(forcedTimestamp = Some(previousBlockTimestamp + epochLengthInSeconds * 2))
    generateBlock(generationRules, generator)._2
  }

  def generateBlockWithIncorrectNonce(generator: SidechainBlocksGenerator): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes)
    val corruptedRules = generationRules.copy(corruption = generationRules.corruption.copy(consensusNonceShift =  BigInteger.valueOf(42)))
    generateBlock(corruptedRules, generator)._2

  }

  def generateBlockWithIncorrectSlot(generator: SidechainBlocksGenerator): SidechainBlock = {
    val consensusSlotShift = 2
    require(consensusSlotShift <= maximumAvailableShift)

    val timestampSlotShift = 1
    require(timestampSlotShift <= maximumAvailableShift)

    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes)
    val corruptedRules = generationRules.copy(corruption = generationRules.corruption.copy(timestampShiftInSlots = timestampSlotShift, consensusSlotShift = consensusSlotShift))
    generateBlock(corruptedRules, generator)._2

  }

  def generateBlockWithIncorrectVrfPublicKey(generator: SidechainBlocksGenerator): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes)
    val forgerBoxCorruption = ForgerBoxCorruptionRules(vrfPubKeyChanged = true)
    val corruptedRules =
      generationRules.copy(corruption = generationRules.corruption.copy(forgerBoxCorruptionRules = Some(forgerBoxCorruption)))
    generateBlock(corruptedRules, generator)._2
  }

  def generateBlockWithIncorrectVrfProof(generator: SidechainBlocksGenerator): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes)
    val corruptedRules =
      generationRules.copy(corruption = generationRules.corruption.copy(forcedVrfProof = Some(VrfGenerator.generateProof(rnd.nextLong()))))
    generateBlock(corruptedRules, generator)._2
  }

  def generateBlockWithIncorrectForgerBoxRewardProposition(generator: SidechainBlocksGenerator): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes)
    val forgerBoxCorruption = ForgerBoxCorruptionRules(rewardPropositionChanged = true)
    val corruptedRules =
      generationRules.copy(corruption = generationRules.corruption.copy(forgerBoxCorruptionRules = Some(forgerBoxCorruption)))
    generateBlock(corruptedRules, generator)._2
  }

  def generateBlockWithIncorrectForgerBoxProposition(generator: SidechainBlocksGenerator): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes)
    val forgerBoxCorruption = ForgerBoxCorruptionRules(propositionChanged = true)
    val corruptedRules =
      generationRules.copy(corruption = generationRules.corruption.copy(forgerBoxCorruptionRules = Some(forgerBoxCorruption)))
    generateBlock(corruptedRules, generator)._2
  }

  def generateBlockWithIncorrectForgerBoxNonce(generator: SidechainBlocksGenerator): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes)
    val forgerBoxCorruption = ForgerBoxCorruptionRules(nonceShift = 1)
    val corruptedRules =
      generationRules.copy(corruption = generationRules.corruption.copy(forgerBoxCorruptionRules = Some(forgerBoxCorruption)))
    generateBlock(corruptedRules, generator)._2
  }

  def generateBlockWithIncorrectForgerBoxValue(generator: SidechainBlocksGenerator): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes)
    val forgerBoxCorruption = ForgerBoxCorruptionRules(valueShift = 1)
    val corruptedRules =
      generationRules.copy(corruption = generationRules.corruption.copy(forgerBoxCorruptionRules = Some(forgerBoxCorruption)))
    generateBlock(corruptedRules, generator)._2
  }

  def generateBlockWithNotEnoughStake(generator: SidechainBlocksGenerator): SidechainBlock = {
    val generationRules = GenerationRules.generateCorrectGenerationRules(rnd, generator.getNotSpentBoxes)
    val corruptedRules =
      generationRules.copy(corruption = generationRules.corruption.copy(stakeCheckCorruption = true))
    generateBlock(corruptedRules, generator)._2
  }
}
