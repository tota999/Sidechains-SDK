package com.horizen.validation

import com.horizen.SidechainHistory
import com.horizen.block.{Ommer, SidechainBlock, SidechainBlockHeader}
import com.horizen.chain.SidechainBlockInfo
import com.horizen.consensus.{ConsensusEpochNumber, _}
import com.horizen.fixtures.{CompanionsFixture, SidechainBlockFixture, TransactionFixture, VrfGenerator}
import com.horizen.params.{MainNetParams, NetworkParams}
import com.horizen.proof.VrfProof
import com.horizen.vrf.VrfOutput
import org.junit.Assert.{assertArrayEquals, assertEquals, fail => jFail}
import org.junit.Test
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatest.junit.JUnitSuite
import org.scalatest.mockito.MockitoSugar
import scorex.util.ModifierId

import scala.util.{Failure, Success, Try}

class ConsensusValidatorOmmersTest
  extends JUnitSuite
    with MockitoSugar
    with CompanionsFixture
    with TransactionFixture
    with SidechainBlockFixture {

  val consensusValidator: ConsensusValidator = new ConsensusValidator {
    // always successful
    override private[horizen] def verifyForgerBox(header: SidechainBlockHeader, stakeConsensusEpochInfo: StakeConsensusEpochInfo, vrfOutput: VrfOutput): Unit = {}
  }


  @Test
  def emptyOmmersValidation(): Unit = {
    // Mock history
    val history = mockHistory()

    // Mock block with no ommers
    val parentId: ModifierId = getRandomBlockId()
    val parentInfo: SidechainBlockInfo = mock[SidechainBlockInfo]
    val verifiedBlock: SidechainBlock = mock[SidechainBlock]
    Mockito.when(verifiedBlock.ommers).thenReturn(Seq())

    // Mock other data
    val currentFullConsensusEpochInfo: FullConsensusEpochInfo = mock[FullConsensusEpochInfo]
    val previousFullConsensusEpochInfo: FullConsensusEpochInfo = mock[FullConsensusEpochInfo]

    Try {
      consensusValidator.verifyOmmers(verifiedBlock, currentFullConsensusEpochInfo, previousFullConsensusEpochInfo, parentId, parentInfo, history, Seq())
    } match {
      case Success(_) =>
      case Failure(e) => throw e // jFail(s"Block with no ommers expected to be Valid, instead exception: ${e.getMessage}")
    }
  }

  @Test
  def sameEpochOmmersValidation(): Unit = {
    // Mock history
    val history = mockHistory()

    // Mock other data
    val currentEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(currentEpochNonceBytes)
    val currentFullConsensusEpochInfo = FullConsensusEpochInfo(mock[StakeConsensusEpochInfo],
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(currentEpochNonceBytes)))

    val previousEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(previousEpochNonceBytes)
    val previousFullConsensusEpochInfo = FullConsensusEpochInfo(mock[StakeConsensusEpochInfo],
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(previousEpochNonceBytes)))

    // Test 1: Valid Ommers in correct order from the same epoch as VerifiedBlock
    // Mock ommers
    val ommers: Seq[Ommer] = Seq(
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 3, ConsensusSlotNumber @@ 7)),
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 3, ConsensusSlotNumber @@ 8))
    )

    // Mock block with ommers
    val parentId: ModifierId = getRandomBlockId()
    val parentInfo: SidechainBlockInfo = mock[SidechainBlockInfo]
    val verifiedBlockTimestamp = history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 3, ConsensusSlotNumber @@ 11)
    val verifiedBlock: SidechainBlock = mock[SidechainBlock]
    val header = mock[SidechainBlockHeader]
    Mockito.when(header.timestamp).thenReturn(verifiedBlockTimestamp)
    Mockito.when(verifiedBlock.header).thenReturn(header)
    Mockito.when(verifiedBlock.ommers).thenReturn(ommers)

    val currentEpochConsensusValidator = new ConsensusValidator {
      override private[horizen] def verifyForgerBox(header: SidechainBlockHeader, stakeConsensusEpochInfo: StakeConsensusEpochInfo, vrfOutput: VrfOutput): Unit = {
        assertEquals("Different stakeConsensusEpochInfo expected", currentFullConsensusEpochInfo.stakeConsensusEpochInfo, stakeConsensusEpochInfo)
      }
    }

    Try {
      currentEpochConsensusValidator.verifyOmmers(verifiedBlock, currentFullConsensusEpochInfo, previousFullConsensusEpochInfo, parentId, parentInfo, history, Seq())
    } match {
      case Success(_) =>
      case Failure(e) => throw e // jFail(s"Block with ommers from the same epoch expected to be Valid, instead exception: ${e.getMessage}")
    }


    // Test 2: Same as above, but Ommers contains invalid forger box data
    val fbException = new Exception("ForgerBoxException")
    val forgerBoxFailConsensusValidator = new ConsensusValidator {
      // always fail
      override private[horizen] def verifyForgerBox(header: SidechainBlockHeader, stakeConsensusEpochInfo: StakeConsensusEpochInfo, vrfOutput: VrfOutput): Unit = throw fbException
    }

    Try {
      forgerBoxFailConsensusValidator.verifyOmmers(verifiedBlock, currentFullConsensusEpochInfo, previousFullConsensusEpochInfo, parentId, parentInfo, history, Seq())
    } match {
      case Success(_) => jFail("Block with no ommers expected to be invalid.")
      case Failure(e) => assertEquals("Different exception expected.", fbException, e)
    }


    // Test 3: Valid ommers with valid subommers in correct order from the same epoch as VerifiedBlock
    val ommersWithSubommers: Seq[Ommer] = Seq(
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 3, ConsensusSlotNumber @@ 9), ommers), // with subommers for 3/7, 3/8
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 3, ConsensusSlotNumber @@ 10))
    )
    Mockito.when(verifiedBlock.ommers).thenReturn(ommersWithSubommers)

    Try {
      currentEpochConsensusValidator.verifyOmmers(verifiedBlock, currentFullConsensusEpochInfo, previousFullConsensusEpochInfo, parentId, parentInfo, history, Seq())
    } match {
      case Success(_) =>
      case Failure(e) => throw e // jFail(s"Block with ommers from the same epoch expected to be Valid, instead exception: ${e.getMessage}")
    }
  }

  @Test
  def previousEpochOmmersValidation(): Unit = {
    val verifiedBlockId: ModifierId = getRandomBlockId(1000L)

    // Mock other data
    val currentEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(currentEpochNonceBytes)
    val currentFullConsensusEpochInfo = FullConsensusEpochInfo(mock[StakeConsensusEpochInfo],
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(currentEpochNonceBytes)))

    val previousEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(previousEpochNonceBytes)
    val previousFullConsensusEpochInfo = FullConsensusEpochInfo(mock[StakeConsensusEpochInfo],
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(previousEpochNonceBytes)))

    // Mock history
    val history = mockHistory()

    // Test 1: Valid Ommers in correct order from the previous epoch to VerifiedBlock
    // Mock ommers
    val ommers: Seq[Ommer] = Seq(
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 2, ConsensusSlotNumber @@ 20)),
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 2, ConsensusSlotNumber @@ 21))
    )

    // Mock block with ommers
    val parentId: ModifierId = getRandomBlockId()
    val parentInfo: SidechainBlockInfo = mock[SidechainBlockInfo]
    val verifiedBlockTimestamp = history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 3, ConsensusSlotNumber @@ 10)
    val verifiedBlock: SidechainBlock = mock[SidechainBlock]
    Mockito.when(verifiedBlock.id).thenReturn(verifiedBlockId)
    val header = mock[SidechainBlockHeader]
    Mockito.when(header.timestamp).thenReturn(verifiedBlockTimestamp)
    Mockito.when(verifiedBlock.header).thenReturn(header)
    Mockito.when(verifiedBlock.ommers).thenReturn(ommers)

    val previousEpochConsensusValidator = new ConsensusValidator {
      override private[horizen] def verifyForgerBox(header: SidechainBlockHeader, stakeConsensusEpochInfo: StakeConsensusEpochInfo, vrfOutput: VrfOutput): Unit = {
        assertEquals("Different stakeConsensusEpochInfo expected", previousFullConsensusEpochInfo.stakeConsensusEpochInfo, stakeConsensusEpochInfo)
      }
    }

    Try {
      previousEpochConsensusValidator.verifyOmmers(verifiedBlock, currentFullConsensusEpochInfo, previousFullConsensusEpochInfo, parentId, parentInfo, history, Seq())
    } match {
      case Success(_) =>
      case Failure(e) => throw e // jFail(s"Block with ommers from previous epoch only expected to be Valid, instead exception: ${e.getMessage}")
    }


    // Test 2: Valid ommers with valid subommers in correct order from previous epoch to VerifiedBlock
    val anotherOmmers: Seq[Ommer] = Seq(
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 2, ConsensusSlotNumber @@ 44)),
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 2, ConsensusSlotNumber @@ 46))
    )
    val ommersWithSubommers: Seq[Ommer] = Seq(
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 2, ConsensusSlotNumber @@ 40), ommers), // with subommers for 2/20, 2/21
      getMockedOmmer(history.getTimeStampForEpochAndSlot(ConsensusEpochNumber @@ 2, ConsensusSlotNumber @@ 50), anotherOmmers) // with subommers for 2/44, 2/46
    )
    Mockito.when(verifiedBlock.ommers).thenReturn(ommersWithSubommers)

    Try {
      previousEpochConsensusValidator.verifyOmmers(verifiedBlock, currentFullConsensusEpochInfo, previousFullConsensusEpochInfo, parentId, parentInfo, history, Seq())
    } match {
      case Success(_) =>
      case Failure(e) => throw e // jFail(s"Block with ommers from the same epoch expected to be Valid, instead exception: ${e.getMessage}")
    }
  }

  @Test
  def switchingEpochOmmersValidation(): Unit = {
    // Mock Consensus epoch info data
    val currentEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(currentEpochNonceBytes)
    val currentFullConsensusEpochInfo = FullConsensusEpochInfo(mock[StakeConsensusEpochInfo],
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(currentEpochNonceBytes)))

    val previousEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(previousEpochNonceBytes)
    val previousFullConsensusEpochInfo = FullConsensusEpochInfo(mock[StakeConsensusEpochInfo],
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(previousEpochNonceBytes)))

    val switchedOmmersCurrentEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(switchedOmmersCurrentEpochNonceBytes)
    val switchedOmmersFullConsensusEpochInfo = FullConsensusEpochInfo(currentFullConsensusEpochInfo.stakeConsensusEpochInfo,
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(switchedOmmersCurrentEpochNonceBytes)))


    /* Test 1: Valid Ommers in correct order from the previous and current epoch as VerifiedBlock
      Notation <epoch_number>/<slot_number>
      Slots in epoch:   6
      Block slots number:   2/2 - 3/6
                                   |
      Ommers slots:   [2/5    ,   3/1   ,   3/5]
      Ommer 2/5 is in `quite` slots, so for 3/6 block and 3/1 ommer nonce will be the same.
    */


    // Mock history
    var slotsInEpoch: Int = 6
    var history = mockHistory(slotsInEpoch)

    val previousEpochNumber: ConsensusEpochNumber = ConsensusEpochNumber @@ 2
    val currentEpochNumber: ConsensusEpochNumber = ConsensusEpochNumber @@ 3

    // Mock ommers
    val ommers: Seq[Ommer] = Seq(
      getMockedOmmer(history.getTimeStampForEpochAndSlot(previousEpochNumber, ConsensusSlotNumber @@ 5)), // quite slot - no impact on nonce calculation
      getMockedOmmer(history.getTimeStampForEpochAndSlot(currentEpochNumber, ConsensusSlotNumber @@ 1)),
      getMockedOmmer(history.getTimeStampForEpochAndSlot(currentEpochNumber, ConsensusSlotNumber @@ 5))
    )

    // Mock block with ommers
    val parentId: ModifierId = getRandomBlockId()
    val parentInfo: SidechainBlockInfo = mock[SidechainBlockInfo]
    val verifiedBlockTimestamp = history.getTimeStampForEpochAndSlot(currentEpochNumber, ConsensusSlotNumber @@ 6)
    val verifiedBlock: SidechainBlock = mock[SidechainBlock]
    val header = mock[SidechainBlockHeader]
    Mockito.when(header.timestamp).thenReturn(verifiedBlockTimestamp)
    Mockito.when(verifiedBlock.header).thenReturn(header)
    Mockito.when(verifiedBlock.ommers).thenReturn(ommers)


    Mockito.when(history.calculateNonceForNonGenesisEpoch(
      ArgumentMatchers.any[ModifierId],
      ArgumentMatchers.any[SidechainBlockInfo],
      ArgumentMatchers.any[Seq[(VrfOutput, ConsensusSlotNumber)]])).thenAnswer(answer => {
      val lastBlockIdInEpoch: ModifierId = answer.getArgument(0)
      val lastBlockInfoInEpoch: SidechainBlockInfo = answer.getArgument(1)
      val acc: Seq[(VrfOutput, ConsensusSlotNumber)] = answer.getArgument(2)

      assertEquals("On calculate nonce: lastBlockIdInEpoch is different", parentId, lastBlockIdInEpoch)
      assertEquals("On calculate nonce: lastBlockInfoInEpoch is different", parentInfo, lastBlockInfoInEpoch)
      val expectedAcc: Seq[(VrfOutput, ConsensusSlotNumber)] = Seq(
        (null, ConsensusSlotNumber @@ 5)
      )
      assertEquals("On calculate nonce: acc is different", expectedAcc, acc)

      // Return nonce same as current epoch nonce
      currentFullConsensusEpochInfo.nonceConsensusEpochInfo
    })

    var switchedEpochConsensusValidator = new ConsensusValidator {
      /*override def verifyVrfProofAndHash(history: SidechainHistory, header: SidechainBlockHeader, message: VrfMessage): Unit = {
        val epochAndSlot = history.timestampToEpochAndSlot(header.timestamp)

        val expectedVrfMessage: VrfMessage = epochAndSlot.epochNumber match {
          case `previousEpochNumber` => buildVrfMessage(epochAndSlot.slotNumber, previousFullConsensusEpochInfo.nonceConsensusEpochInfo)
          case `currentEpochNumber` => buildVrfMessage(epochAndSlot.slotNumber, currentFullConsensusEpochInfo.nonceConsensusEpochInfo)
        }
        assertArrayEquals("Different vrf message expected", expectedVrfMessage, message)
      }*/
      override private[horizen] def verifyForgerBox(header: SidechainBlockHeader, stakeConsensusEpochInfo: StakeConsensusEpochInfo, vrfOutput: VrfOutput): Unit = {
        val epochAndSlot = history.timestampToEpochAndSlot(header.timestamp)
        epochAndSlot.epochNumber match {
          case `previousEpochNumber` => assertEquals("Different stakeConsensusEpochInfo expected", previousFullConsensusEpochInfo.stakeConsensusEpochInfo, stakeConsensusEpochInfo)
          case `currentEpochNumber` => assertEquals("Different stakeConsensusEpochInfo expected", currentFullConsensusEpochInfo.stakeConsensusEpochInfo, stakeConsensusEpochInfo)
          case epoch => jFail(s"Unknown epoch number: $epoch")
        }
      }
    }

    Try {
      switchedEpochConsensusValidator.verifyOmmers(verifiedBlock, currentFullConsensusEpochInfo, previousFullConsensusEpochInfo, parentId, parentInfo, history, Seq())
    } match {
      case Success(_) =>
      case Failure(e) => throw e // jFail(s"Block with ommers from both the same and previous epoch expected to be Valid, instead exception: ${e.getMessage}")
    }


    /* Test 2: Valid Ommers in correct order from the previous and current epoch as VerifiedBlock
       Notation <epoch_number>/<slot_number>
       Slots in epoch:   6
       Block slots number:   2/2 - 3/6
                                    |
       Ommers slots:   [2/3    ,   2/4   ,   3/5]
       Ommers 2/3;2/4 is in `active` slots for nonce calculation, so for block 3/6 and ommer 3/5 nonce will be different.
     */

    slotsInEpoch = 6
    history = mockHistory(slotsInEpoch)

    val anotherOmmers: Seq[Ommer] = Seq(
      getMockedOmmer(history.getTimeStampForEpochAndSlot(previousEpochNumber, ConsensusSlotNumber @@ 3)), // active slot - has impact on nonce calculation
      getMockedOmmer(history.getTimeStampForEpochAndSlot(previousEpochNumber, ConsensusSlotNumber @@ 4)), // active slot - has impact on nonce calculation
      getMockedOmmer(history.getTimeStampForEpochAndSlot(currentEpochNumber, ConsensusSlotNumber @@ 5))
    )

    Mockito.when(verifiedBlock.ommers).thenReturn(anotherOmmers)

    Mockito.when(history.calculateNonceForNonGenesisEpoch(
      ArgumentMatchers.any[ModifierId],
      ArgumentMatchers.any[SidechainBlockInfo],
      ArgumentMatchers.any[Seq[(VrfOutput, ConsensusSlotNumber)]])).thenAnswer(answer => {
      val lastBlockIdInEpoch: ModifierId = answer.getArgument(0)
      val lastBlockInfoInEpoch: SidechainBlockInfo = answer.getArgument(1)
      val acc: Seq[(VrfOutput, ConsensusSlotNumber)] = answer.getArgument(2)

      assertEquals("On calculate nonce: lastBlockIdInEpoch is different", parentId, lastBlockIdInEpoch)
      assertEquals("On calculate nonce: lastBlockInfoInEpoch is different", parentInfo, lastBlockInfoInEpoch)
      val expectedAcc: Seq[(VrfOutput, ConsensusSlotNumber)] = Seq(
        (null, ConsensusSlotNumber @@ 4),
        (null, ConsensusSlotNumber @@ 3)
      )
      assertEquals("On calculate nonce: acc is different", expectedAcc, acc)

      // Return nonce same different from current epoch nonce
      switchedOmmersFullConsensusEpochInfo.nonceConsensusEpochInfo
    })

    switchedEpochConsensusValidator = new ConsensusValidator {
      /*override def verifyVrfProofAndHash(history: SidechainHistory, header: SidechainBlockHeader, message: VrfMessage): Unit = {
        val epochAndSlot = history.timestampToEpochAndSlot(header.timestamp)

        val expectedVrfMessage: VrfMessage = epochAndSlot.epochNumber match {
          case `previousEpochNumber` => buildVrfMessage(epochAndSlot.slotNumber, previousFullConsensusEpochInfo.nonceConsensusEpochInfo)
          // NOTE: different nonce to current epoch nonce expected.
          case `currentEpochNumber` => buildVrfMessage(epochAndSlot.slotNumber, switchedOmmersFullConsensusEpochInfo.nonceConsensusEpochInfo)
        }
        assertArrayEquals("Different vrf message expected", expectedVrfMessage, message)
      }*/
      override private[horizen] def verifyForgerBox(header: SidechainBlockHeader, stakeConsensusEpochInfo: StakeConsensusEpochInfo, vrfOutput: VrfOutput): Unit = {
        val epochAndSlot = history.timestampToEpochAndSlot(header.timestamp)
        epochAndSlot.epochNumber match {
          case `previousEpochNumber` => assertEquals("Different stakeConsensusEpochInfo expected", previousFullConsensusEpochInfo.stakeConsensusEpochInfo, stakeConsensusEpochInfo)
          case `currentEpochNumber` => assertEquals("Different stakeConsensusEpochInfo expected", switchedOmmersFullConsensusEpochInfo.stakeConsensusEpochInfo, stakeConsensusEpochInfo)
          case epoch => jFail(s"Unknown epoch number: $epoch")
        }
      }
    }

    Try {
      switchedEpochConsensusValidator.verifyOmmers(verifiedBlock, currentFullConsensusEpochInfo, previousFullConsensusEpochInfo, parentId, parentInfo, history, Seq())
    } match {
      case Success(_) =>
      case Failure(e) => throw e // jFail(s"Block with ommers from both the same and previous epoch expected to be Valid, instead exception: ${e.getMessage}")
    }
  }

  @Test
  def switchingEpochOmmersWithSubOmmersValidation(): Unit = {
    // Mock Consensus epoch info data
    val currentEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(currentEpochNonceBytes)
    val currentFullConsensusEpochInfo = FullConsensusEpochInfo(mock[StakeConsensusEpochInfo],
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(currentEpochNonceBytes)))

    val previousEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(previousEpochNonceBytes)
    val previousFullConsensusEpochInfo = FullConsensusEpochInfo(mock[StakeConsensusEpochInfo],
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(previousEpochNonceBytes)))

    val switchedOmmersCurrentEpochNonceBytes: Array[Byte] = new Array[Byte](32)
    scala.util.Random.nextBytes(switchedOmmersCurrentEpochNonceBytes)
    val switchedOmmersFullConsensusEpochInfo = FullConsensusEpochInfo(currentFullConsensusEpochInfo.stakeConsensusEpochInfo,
      NonceConsensusEpochInfo(byteArrayToConsensusNonce(switchedOmmersCurrentEpochNonceBytes)))


    /* Test 1: Valid Ommers with subommers in correct order from the previous and current epoch as VerifiedBlock
       Notation <epoch_number>/<slot_number>
       Slots in epoch:   6
       Block slots number:   2/2 - 3/5
                                    |
       Ommers slots:   [2/4    ,   2/6   ,   3/4]
                         |          |         |
       Subommers slots:[2/3]      [2/5]  [3/2 , 3/3]
                                           |
       Subommers slots:                  [3/1]
       Ommer 2/3 is in `active` slots for nonce calculation, so for block 3/5 and ommers 3/1; 3/2; 3/3; 3/4 nonce will be different.
     */


    // Mock history
    val slotsInEpoch: Int = 6
    val history = mockHistory(slotsInEpoch)

    val previousEpochNumber: ConsensusEpochNumber = ConsensusEpochNumber @@ 2
    val currentEpochNumber: ConsensusEpochNumber = ConsensusEpochNumber @@ 3

    // Mock ommers
    val ommers: Seq[Ommer] = Seq(
      getMockedOmmer(history.getTimeStampForEpochAndSlot(previousEpochNumber, ConsensusSlotNumber @@ 4),  // active slot - has impact on nonce calculation
        Seq(
          getMockedOmmer(history.getTimeStampForEpochAndSlot(previousEpochNumber, ConsensusSlotNumber @@ 3))
        )),

      getMockedOmmer(history.getTimeStampForEpochAndSlot(previousEpochNumber, ConsensusSlotNumber @@ 6), // quite slot - no impact on nonce calculation
        Seq(
          getMockedOmmer(history.getTimeStampForEpochAndSlot(previousEpochNumber, ConsensusSlotNumber @@ 5))
        )),

      getMockedOmmer(history.getTimeStampForEpochAndSlot(currentEpochNumber, ConsensusSlotNumber @@ 4),
        Seq(
          getMockedOmmer(history.getTimeStampForEpochAndSlot(currentEpochNumber, ConsensusSlotNumber @@ 2),
            Seq(
              getMockedOmmer(history.getTimeStampForEpochAndSlot(currentEpochNumber, ConsensusSlotNumber @@ 1))
            )),
          getMockedOmmer(history.getTimeStampForEpochAndSlot(currentEpochNumber, ConsensusSlotNumber @@ 3))
        ))
    )

    // Mock block with ommers
    val parentId: ModifierId = getRandomBlockId()
    val parentInfo: SidechainBlockInfo = mock[SidechainBlockInfo]
    val verifiedBlockTimestamp = history.getTimeStampForEpochAndSlot(currentEpochNumber, ConsensusSlotNumber @@ 5)
    val verifiedBlock: SidechainBlock = mock[SidechainBlock]
    val header = mock[SidechainBlockHeader]
    Mockito.when(header.timestamp).thenReturn(verifiedBlockTimestamp)
    Mockito.when(verifiedBlock.header).thenReturn(header)
    Mockito.when(verifiedBlock.ommers).thenReturn(ommers)


    Mockito.when(history.calculateNonceForNonGenesisEpoch(
      ArgumentMatchers.any[ModifierId],
      ArgumentMatchers.any[SidechainBlockInfo],
      ArgumentMatchers.any[Seq[(VrfOutput, ConsensusSlotNumber)]])).thenAnswer(answer => {
      val lastBlockIdInEpoch: ModifierId = answer.getArgument(0)
      val lastBlockInfoInEpoch: SidechainBlockInfo = answer.getArgument(1)
      val acc: Seq[(VrfOutput, ConsensusSlotNumber)] = answer.getArgument(2)

      assertEquals("On calculate nonce: lastBlockIdInEpoch is different", parentId, lastBlockIdInEpoch)
      assertEquals("On calculate nonce: lastBlockInfoInEpoch is different", parentInfo, lastBlockInfoInEpoch)
      val expectedAcc: Seq[(VrfOutput, ConsensusSlotNumber)] = Seq(
        (null, ConsensusSlotNumber @@ 6),
        (null, ConsensusSlotNumber @@ 4)
      )
      assertEquals("On calculate nonce: acc is different", expectedAcc, acc)

      // Return nonce same different from current epoch nonce
      switchedOmmersFullConsensusEpochInfo.nonceConsensusEpochInfo
    })

    val switchedEpochConsensusValidator = new ConsensusValidator {
      /*override def verifyVrfProofAndHash(history: SidechainHistory, header: SidechainBlockHeader, message: VrfMessage): Unit = {
        val epochAndSlot = history.timestampToEpochAndSlot(header.timestamp)

        val expectedVrfMessage: VrfMessage = epochAndSlot.epochNumber match {
          case `previousEpochNumber` => buildVrfMessage(epochAndSlot.slotNumber, previousFullConsensusEpochInfo.nonceConsensusEpochInfo)
          // NOTE: different nonce to current epoch nonce expected.
          case `currentEpochNumber` => buildVrfMessage(epochAndSlot.slotNumber, switchedOmmersFullConsensusEpochInfo.nonceConsensusEpochInfo)
        }
        assertArrayEquals("Different vrf message expected", expectedVrfMessage, message)
      }*/
      override private[horizen] def verifyForgerBox(header: SidechainBlockHeader, stakeConsensusEpochInfo: StakeConsensusEpochInfo, vrfOutput: VrfOutput): Unit = {
        val epochAndSlot = history.timestampToEpochAndSlot(header.timestamp)
        epochAndSlot.epochNumber match {
          case `previousEpochNumber` => assertEquals("Different stakeConsensusEpochInfo expected", previousFullConsensusEpochInfo.stakeConsensusEpochInfo, stakeConsensusEpochInfo)
          case `currentEpochNumber` => assertEquals("Different stakeConsensusEpochInfo expected", switchedOmmersFullConsensusEpochInfo.stakeConsensusEpochInfo, stakeConsensusEpochInfo)
          case epoch => jFail(s"Unknown epoch number: $epoch")
        }
      }
    }

    Try {
      switchedEpochConsensusValidator.verifyOmmers(verifiedBlock, currentFullConsensusEpochInfo, previousFullConsensusEpochInfo, parentId, parentInfo, history, Seq())
    } match {
      case Success(_) =>
      case Failure(e) => throw e // jFail(s"Block with ommers from both the same and previous epoch expected to be Valid, instead exception: ${e.getMessage}")
    }

  }

  private def getMockedOmmer(timestamp: Long, subOmmers: Seq[Ommer] = Seq()): Ommer = {
    val header = mock[SidechainBlockHeader]
    Mockito.when(header.timestamp).thenReturn(timestamp)

    Ommer(header, None, Seq(), subOmmers)
  }

  private def mockHistory(slotsInEpoch: Int = 720): SidechainHistory = {
    val params: NetworkParams = MainNetParams(consensusSlotsInEpoch = slotsInEpoch)
    // Because TimeToEpochSlotConverter is a trait, we need to do this dirty stuff to use its methods as a part of mocked SidechainHistory
    class TimeToEpochSlotConverterImpl(val params: NetworkParams) extends TimeToEpochSlotConverter
    val converter = new TimeToEpochSlotConverterImpl(params)

    val history: SidechainHistory = mock[SidechainHistory]
    Mockito.when(history.timeStampToEpochNumber(ArgumentMatchers.any[Long])).thenAnswer(answer => {
      converter.timeStampToEpochNumber(answer.getArgument(0))
    })

    Mockito.when(history.timeStampToSlotNumber(ArgumentMatchers.any[Long])).thenAnswer(answer => {
      converter.timeStampToSlotNumber(answer.getArgument(0))
    })

    Mockito.when(history.timeStampToAbsoluteSlotNumber(ArgumentMatchers.any[Long])).thenAnswer(answer => {
      converter.timeStampToAbsoluteSlotNumber(answer.getArgument(0))
    })

    Mockito.when(history.timestampToEpochAndSlot(ArgumentMatchers.any[Long])).thenAnswer(answer => {
      converter.timestampToEpochAndSlot(answer.getArgument(0))
    })

    Mockito.when(history.getTimeStampForEpochAndSlot(ArgumentMatchers.any[ConsensusEpochNumber], ArgumentMatchers.any[ConsensusSlotNumber])).thenAnswer(answer => {
      converter.getTimeStampForEpochAndSlot(answer.getArgument(0), answer.getArgument(1))
    })

    Mockito.when(history.params).thenReturn(params)

    Mockito.when(history.getVrfOutput(ArgumentMatchers.any[SidechainBlockHeader], ArgumentMatchers.any[NonceConsensusEpochInfo])).thenAnswer(answer => {
      val blockHeader: SidechainBlockHeader = answer.getArgument(0)
      Some(null) //Some(VrfGenerator.generateVrfOutput(blockHeader.timestamp))
    })

    history
  }
}