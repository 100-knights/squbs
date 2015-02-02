package org.squbs.cluster

import akka.testkit.ImplicitSender
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}

import scala.concurrent.duration._

/**
  * Created by zhuwang on 1/23/15.
 */
class ZkClusterNormalTest extends ZkClusterMultiActorSystemTestKit("ZkClusterNormalTest")
  with ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  val timeout = 30 seconds

  val clusterSize = 6
  
  override def afterEach(): Unit = {
    println("------------------------------------------------------------------------------------------")
    Thread.sleep(timeout.toMillis / 10)
  }
  
  override def beforeAll = startCluster
  override def afterAll = shutdownCluster

  "ZkCluster" should "elect the leader and sync with or the members" in {
    // query the leader from any member
    val anyMember = pickASystemRandomly()
    zkClusterExts(anyMember) tell (ZkQueryLeadership, self)
    val leader = expectMsgType[ZkLeadership](timeout)
    // leader information should be in sync across all members
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryLeadership, self)
        expectMsg(timeout, leader)
    }
  }

  "ZkCluster" should "not change leader if add or remove follower" in {
    // query the leader
    zkClusterExts(0) tell (ZkQueryLeadership, self)
    val leader = expectMsgType[ZkLeadership](timeout)
    // kill any follower
    val leaderName = leader.address.system
    val toBeKilled = pickASystemRandomly(Some(leaderName))
    killSystem(toBeKilled)
    Thread.sleep(timeout.toMillis / 10)
    // leader should not change across the cluster
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryLeadership, self)
        expectMsg(timeout, leader)
    }
    // add back the follower
    bringUpSystem(toBeKilled)
    Thread.sleep(timeout.toMillis / 10)
    // leader should not change across the cluster
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryLeadership, self)
        expectMsg(timeout, leader)
    }
  }

  "ZkCluster" should "change leader if the leader left" in {
    // query the leader
    zkClusterExts(0) tell (ZkQueryLeadership, self)
    val leader = expectMsgType[ZkLeadership](timeout)
    // kill the leader
    val toBeKilled = leader.address.system
    killSystem(toBeKilled)
    Thread.sleep(timeout.toMillis / 10)
    // a new leader should be elected among the remaining followers
    zkClusterExts(pickASystemRandomly()) tell (ZkQueryLeadership, self)
    val newLeader = expectMsgType[ZkLeadership](timeout)
    newLeader should not be (leader)
    // the remaining members should have the same information about the new leader
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryLeadership, self)
        expectMsg(timeout, newLeader)
    }
    // add back the previous leader
    bringUpSystem(toBeKilled)
    Thread.sleep(timeout.toMillis / 10)
    // the new leader should not change across the cluster
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryLeadership, self)
        expectMsg(timeout, newLeader)
    }
  }

  "ZkCluster" should "keep members set in sync" in {
    // members information should be in sync across all members
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryMembership, self)
        expectMsgType[ZkMembership](timeout).members map (_.system) should be ((0 until clusterSize).map(int2SystemName).toSet)
    }
  }
  
  "ZkCluster" should "update the members set if a follower left" in {
    // query the leader
    zkClusterExts(0) tell (ZkQueryLeadership, self)
    val leader = expectMsgType[ZkLeadership](timeout)
    // kill any follower
    val leaderName = leader.address.system
    val toBeKilled = pickASystemRandomly(Some(leaderName))
    killSystem(toBeKilled)
    Thread.sleep(timeout.toMillis / 10)
    // now the every one should get members set up to date
    val originalMembers = (0 until clusterSize).map(int2SystemName).toSet
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryMembership, self)
        expectMsgType[ZkMembership](timeout).members map (_.system) should be (originalMembers - toBeKilled)
    }
    // add back the previous leader
    bringUpSystem(toBeKilled)
    Thread.sleep(timeout.toMillis / 10)
    // now the every one should get members set up to date
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryMembership, self)
        expectMsgType[ZkMembership](timeout).members map (_.system) should be (originalMembers)
    }
  }

  "ZkCluster" should "update the members set if a leader left" in {
    // query the leader
    zkClusterExts(0) tell (ZkQueryLeadership, self)
    val leader = expectMsgType[ZkLeadership](timeout)
    // kill any follower
    val leaderName = leader.address.system
    killSystem(leaderName)
    Thread.sleep(timeout.toMillis / 10)
    // now the every one should get members set up to date
    val originalMembers = (0 until clusterSize).map(int2SystemName).toSet
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryMembership, self)
        expectMsgType[ZkMembership](timeout).members map (_.system) should be (originalMembers - leaderName)
    }
    // add back the previous leader
    bringUpSystem(leaderName)
    Thread.sleep(timeout.toMillis / 10)
    // now the every one should get members set up to date
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryMembership, self)
        expectMsgType[ZkMembership](timeout).members map (_.system) should be (originalMembers)
    }
  }

  "ZkCluster leader" should "be able to create, resize and delete partition" in {
    // query the leader
    zkClusterExts(0) tell (ZkQueryLeadership, self)
    val leaderName = expectMsgType[ZkLeadership](timeout).address.system
    println(s"Now leader is $leaderName")
    // send partition creation query directly to leader
    val parKey = ByteString("myPar")
    zkClusterExts(leaderName) tell (ZkQueryPartition(parKey, Some("created"), Some(2)), self)
    val partitionInfo = expectMsgType[ZkPartition](timeout)
    println(partitionInfo)
    Thread.sleep(timeout.toMillis / 10)
    // the partition information should be consistent across the cluster
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryPartition(parKey), self)
        expectMsgType[ZkPartition](timeout).members.toSet should be (partitionInfo.members.toSet)
    }
    // send partition resize query directly to leader
    zkClusterExts(leaderName) tell (ZkQueryPartition(parKey, expectedSize = Some(3)), self)
    val resized = expectMsgType[ZkPartition](timeout)
    resized.members.size should be (3)
    Thread.sleep(timeout.toMillis / 10)
    // the resized partition information should be consistent across the cluster
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryPartition(parKey, expectedSize = Some(3)), self)
        expectMsgType[ZkPartition](timeout).members.toSet should be (resized.members.toSet)
    }
    // send partition remove query directly to leader
    zkClusterExts(leaderName) tell (ZkRemovePartition(parKey), self)
    expectMsgType[ZkPartitionRemoval].partitionKey should be (parKey)
    Thread.sleep(timeout.toMillis / 10)
    // query the partition again
    zkClusterExts(leaderName) tell (ZkQueryPartition(parKey), self)
    expectMsgType[ZkPartitionNotFound](timeout).partitionKey should be (parKey)
    // the partition should be removed from the snapshots in every member
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryPartition(parKey), self)
        expectMsgType[ZkPartitionNotFound](timeout).partitionKey should be (parKey)
    }
  }

  "ZkCluster follower" should "respond to create, resize and delete partition" in {
    // query the leader
    zkClusterExts(0) tell (ZkQueryLeadership, self)
    val leaderName = expectMsgType[ZkLeadership](timeout).address.system
    println(s"Now leader is $leaderName")
    // pick up any member other than the leader
    val followerName = pickASystemRandomly(Some(leaderName))
    println(s"pick the follower $followerName")
    // send partition creation query to follower
    val parKey = ByteString("myPar")
    zkClusterExts(followerName) tell (ZkQueryPartition(parKey, Some("created"), Some(2)), self)
    val partitionInfo = expectMsgType[ZkPartition](timeout)
    println(partitionInfo)
    Thread.sleep(timeout.toMillis / 10)
    // the partition information should be consistent across the cluster
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryPartition(parKey), self)
        expectMsgType[ZkPartition](timeout).members.toSet should be (partitionInfo.members.toSet)
    }
    // send partition resize query directly to leader
    zkClusterExts(followerName) tell (ZkQueryPartition(parKey, expectedSize = Some(3)), self)
    val resized = expectMsgType[ZkPartition](timeout)
    resized.members.size should be (3)
    Thread.sleep(timeout.toMillis / 10)
    // the resized partition information should be consistent across the cluster
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryPartition(parKey, expectedSize = Some(3)), self)
        expectMsgType[ZkPartition](timeout).members.toSet should be (resized.members.toSet)
    }
    // send partition remove query follower
    zkClusterExts(followerName) tell (ZkRemovePartition(parKey), self)
    expectMsgType[ZkPartitionRemoval].partitionKey should be (parKey)
    Thread.sleep(timeout.toMillis / 10)
    // query the partition again
    zkClusterExts(followerName) tell (ZkQueryPartition(parKey), self)
    expectMsgType[ZkPartitionNotFound](timeout).partitionKey should be (parKey)
    // the partition should be removed from the snapshots in every member
    zkClusterExts foreach {
      case (name, ext) => ext tell (ZkQueryPartition(parKey), self)
        expectMsgType[ZkPartitionNotFound](timeout).partitionKey should be (parKey)
    }
  }

  "ZkCluster" should "rebalance each partition when follower left or came back" in {
    // create 2 partitions
    val par1 = ByteString("myPar1")
    val par2 = ByteString("myPar2")
    zkClusterExts(pickASystemRandomly()) tell (ZkQueryPartition(par1, Some("created"), Some(3)), self)
    expectMsgType[ZkPartition](timeout)
    zkClusterExts(pickASystemRandomly()) tell (ZkQueryPartition(par2, Some("created"), Some(3)), self)
    expectMsgType[ZkPartition](timeout)
    Thread.sleep(timeout.toMillis / 10)
    // query the leader
    zkClusterExts(0) tell (ZkQueryLeadership, self)
    val leaderName = expectMsgType[ZkLeadership](timeout).address.system
    println(s"Now leader is $leaderName")
    // pick up any member other than the leader
    val followerName = pickASystemRandomly(Some(leaderName))
    println(s"pick the follower $followerName")
    // kill the follower
    killSystem(followerName)
    Thread.sleep(timeout.toMillis / 10)
    // the rebalanced partition should be consistent across the cluster
    zkClusterExts foreach {
      case (name, ext) =>
        ext tell (ZkQueryPartition(par1), self)
        val par1Info = expectMsgType[ZkPartition](timeout)
        par1Info.members.size should be (3)
        par1Info.members.find(_.system == followerName) should be (None)
        ext tell (ZkQueryPartition(par2), self)
        val par2Info = expectMsgType[ZkPartition](timeout)
        par2Info.members.size should be (3)
        par2Info.members.find(_.system == followerName) should be (None)
    }
    // bring up the follower
    bringUpSystem(followerName)
    Thread.sleep(timeout.toMillis / 10)
    // the rebalanced partition should be consistent across the cluster
    zkClusterExts foreach {
      case (name, ext) =>
        ext tell (ZkQueryPartition(par1), self)
        val par1Info = expectMsgType[ZkPartition](timeout)
        par1Info.members.size should be (3)
        ext tell (ZkQueryPartition(par2), self)
        val par2Info = expectMsgType[ZkPartition](timeout)
        par2Info.members.size should be (3)
    }
  }

  "ZkCluster" should "rebalance each partition when leader left or came back" in {
    // create 2 partitions
    val par1 = ByteString("myPar1")
    val par2 = ByteString("myPar2")
    zkClusterExts(pickASystemRandomly()) tell (ZkQueryPartition(par1, Some("created"), Some(3)), self)
    expectMsgType[ZkPartition](timeout)
    zkClusterExts(pickASystemRandomly()) tell (ZkQueryPartition(par2, Some("created"), Some(3)), self)
    expectMsgType[ZkPartition](timeout)
    Thread.sleep(timeout.toMillis / 10)
    // query the leader
    zkClusterExts(0) tell (ZkQueryLeadership, self)
    val originalLeader = expectMsgType[ZkLeadership](timeout).address.system
    println(s"Now leader is $originalLeader")
    // kill the leader
    killSystem(originalLeader)
    Thread.sleep(timeout.toMillis / 10)
    // the rebalanced partition should be consistent across the cluster
    zkClusterExts foreach {
      case (name, ext) =>
        ext tell (ZkQueryPartition(par1), self)
        val par1Info = expectMsgType[ZkPartition](timeout)
        par1Info.members.size should be (3)
        par1Info.members.find(_.system == originalLeader) should be (None)
        ext tell (ZkQueryPartition(par2), self)
        val par2Info = expectMsgType[ZkPartition](timeout)
        par2Info.members.size should be (3)
        par2Info.members.find(_.system == originalLeader) should be (None)
    }
    // bring up the follower
    bringUpSystem(originalLeader)
    Thread.sleep(timeout.toMillis / 10)
    // the rebalanced partition should be consistent across the cluster
    zkClusterExts foreach {
      case (name, ext) =>
        ext tell (ZkQueryPartition(par1), self)
        val par1Info = expectMsgType[ZkPartition](timeout)
        par1Info.members.size should be (3)
        ext tell (ZkQueryPartition(par2), self)
        val par2Info = expectMsgType[ZkPartition](timeout)
        par2Info.members.size should be (3)
    }
  }

}