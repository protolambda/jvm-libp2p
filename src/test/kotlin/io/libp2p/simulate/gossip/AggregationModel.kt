package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.RESULT_INVALID
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteBuf
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.simulate.NetworkStats
import io.libp2p.simulate.Topology
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.WritableStats
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.tools.formatTable
import io.libp2p.tools.get
import io.libp2p.tools.millis
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import io.libp2p.tools.seconds
import io.libp2p.tools.setKeys
import io.libp2p.tools.smartRound
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Random

class AggregationModel {

    val Topic = Topic("Topic-1")
    val AvrgBlockMessageSize = 32 * 1024
    val MaxMissingPeers = 32

    data class GossipStats(
        val msgDelay: WritableStats,
        val someMissingPeers: List<GossipSimPeer> = emptyList()
    )

    data class SimConfig(
        val totalPeers: Int = 10000,
        val badPeers: Int = 0,
        val peerConnections: Int = 10,

        val gossipD: Int = 6,
        val gossipDLow: Int = 3,
        val gossipDHigh: Int = 12,
        val gossipDLazy: Int = 6,
        val gossipAdvertise:Int = 3,
        val gossipHistory: Int = 5,
        val gossipHeartbeat: Duration = 1.seconds,

        val topology: Topology = RandomNPeers(peerConnections),
        val latency: Long = 1L
    )

    data class SimOptions(
        val warmUpDelay: Duration = 5.seconds,
        val zeroHeartbeatsDelay: Duration = 500.millis,
        val manyHeartbeatsDelay: Duration = 30.seconds,
        val generatedNetworksCount: Int = 1,
        val sentMessageCount: Int = 10,
        val startRandomSeed: Long = 0
    )

    data class SimResult(
        val packetCountPerMessage: WritableStats = StatsFactory.DEFAULT.createStats(),
        val trafficPerMessage: WritableStats = StatsFactory.DEFAULT.createStats(),
        val deliveredPart: WritableStats = StatsFactory.DEFAULT.createStats(),
        val deliverDelay: WritableStats = StatsFactory.DEFAULT.createStats()
    ) {
        fun getData() = mapOf(
            "msgCnt" to packetCountPerMessage.getStatisticalSummary().max,
            "traffic" to trafficPerMessage.getStatisticalSummary().max,
            "delivered%" to deliveredPart.getStatisticalSummary().mean,
            "delay(50%)" to deliverDelay.getDescriptiveStatistics().getPercentile(50.0),
            "delay(95%)" to deliverDelay.getDescriptiveStatistics().getPercentile(95.0),
            "delay(max)" to deliverDelay.getDescriptiveStatistics().max
        )
    }

    data class SimDetailedResult (
        val zeroHeartbeats: SimResult = SimResult(),
        val manyHeartbeats: SimResult = SimResult()
    ) {
        fun getData() =
            zeroHeartbeats.getData().setKeys { "0-$it" } +
            manyHeartbeats.getData().setKeys { "N-$it" }
    }

    @Disabled
    @Test
    fun testGossipAggregationModel() {
        val peerConnections = 20
        val cfgs = sequence {
            for (totalPeers in arrayOf(1000, 5000, 10000, 20000, 30000))
                yield(
                    SimConfig(
                        totalPeers = totalPeers,
                        badPeers = (0.1 * totalPeers).toInt(), // TODO Parametrize more?
                        peerConnections = peerConnections,

                        gossipD = 6,
                        gossipDLow = 5,
                        gossipDHigh = 7,
                        gossipDLazy = 6,

                        topology = RandomNPeers(peerConnections),
                        latency = 1L
                    )
                )
        }
        val opt = SimOptions(
            generatedNetworksCount = 50,
            sentMessageCount = 3,
            startRandomSeed = 2
        )

        sim(cfgs, opt)
    }

    fun sim(cfg: Sequence<SimConfig>, opt: SimOptions): List<SimDetailedResult> {
        val res = mutableListOf<SimDetailedResult>()
        for (config in cfg) {
            println("Starting sim: \n\t$config\n\t$opt")
            res += sim(config, opt)
            println("Complete: ${res.last()}")
        }

        println("Results: ")
        println("==============")

        val headers = res[0].getData().keys.joinToString("\t")
        val data = res.map { it.getData().values.map { it.smartRound() }.joinToString("\t") }.joinToString("\n")
        val table = (headers + "\n" + data).formatTable(true)
        println(table)

        return res
    }

    fun sim(cfg: SimConfig, opt: SimOptions): SimDetailedResult {

        val ret = SimDetailedResult()
        for (n in 0 until opt.generatedNetworksCount) {
            val commonRnd = Random(opt.startRandomSeed + n)

            val timeController = TimeControllerImpl()
            println("Creating peers")
            val peers = (0 until cfg.totalPeers).map {
                GossipSimPeer(Topic).apply {

                    // TODO: add message validation, peer aggregation memory, attestation production.

                    routerInstance = GossipRouter().apply {
                        withDConstants(cfg.gossipD, cfg.gossipDLow, cfg.gossipDHigh, cfg.gossipDLazy)
                        gossipSize = cfg.gossipAdvertise
                        gossipHistoryLength = cfg.gossipHistory
                        heartbeatInterval = cfg.gossipHeartbeat
                        serialize = false
                        curTime = timeController::getTime
                        random = commonRnd
                        initHandler {
                            // TODO: parse message, apply strategy propagation constraints
                            // Good/No better: -> propagate
                            // Bad/Worse than available: -> drop, maybe create and propagate aggregate
                            RESULT_VALID
                        }
                    }

                    simExecutor = ControlledExecutorServiceImpl(timeController)
                    msgSizeEstimator = GossipSimPeer.rawPubSubMsgSizeEstimator(AvrgBlockMessageSize)
                    msgDelayer = { cfg.latency }

                    start()
                }
            }
            println("Creating test peers")
            peers[(cfg.totalPeers - cfg.badPeers) until cfg.totalPeers]
                .forEach { it.validationResult = RESULT_INVALID }

            cfg.topology.random = commonRnd

            println("Connecting peers")
            val net = cfg.topology.connect(peers)

            println("Some warm up")
            timeController.addTime(opt.warmUpDelay)

            var lastNS = net.networkStats
            println("Initial stat: $lastNS")
            net.resetStats()

            for (i in 0 until opt.sentMessageCount) {
                println("Sending message #$i...")

                val sentTime = timeController.time
                // TODO change message publishing; make peer publish attestation every X seconds
                peers[i].apiPublisher.publish("Message-$i".toByteArray().toByteBuf(), Topic)

                timeController.addTime(opt.zeroHeartbeatsDelay)

                val receivePeers = peers - peers[i]
                run {
                    val ns = net.networkStats
                    val gs = calcGossipStats(receivePeers, sentTime)
                    ret.zeroHeartbeats.packetCountPerMessage.addValue(ns.msgCount)
                    ret.zeroHeartbeats.trafficPerMessage.addValue(ns.traffic)
                    receivePeers.filter { it.lastMsg != null }
                        .map { it.lastMsgTime - sentTime }
                        .forEach { ret.zeroHeartbeats.deliverDelay.addValue(it) }
                    ret.zeroHeartbeats.deliveredPart.addValue(gs.msgDelay.getCount().toDouble() / receivePeers.size)
                    println("Zero heartbeats: $ns\t\t$gs")
                }

                timeController.addTime(opt.manyHeartbeatsDelay)

                val ns0: NetworkStats
                run {
                    val ns = net.networkStats
                    ns0 = ns
                    val gs = calcGossipStats(receivePeers, sentTime)
                    ret.manyHeartbeats.packetCountPerMessage.addValue(ns.msgCount)
                    ret.manyHeartbeats.trafficPerMessage.addValue(ns.traffic)
                    receivePeers.filter { it.lastMsg != null }
                        .map { it.lastMsgTime - sentTime }
                        .forEach { ret.manyHeartbeats.deliverDelay.addValue(it) }
                    ret.manyHeartbeats.deliveredPart.addValue(gs.msgDelay.getCount().toDouble() / receivePeers.size)
                    println("Many heartbeats: $ns\t\t$gs")
                }

                timeController.addTime(Duration.ofSeconds(10))
                val nsDiff = net.networkStats - ns0
                println("Empty time: $nsDiff")

                net.resetStats()
                clearGossipStats(peers)
            }
        }
        return ret
    }

    private fun clearGossipStats(peers: List<GossipSimPeer>) {
        peers.forEach { it.lastMsg = null }
    }

    private fun calcGossipStats(peers: List<GossipSimPeer>, msgSentTime: Long): GossipStats {
        val stats = StatsFactory.DEFAULT.createStats()
        val missingPeers = mutableListOf<GossipSimPeer>()
        peers.forEach {
            if (it.lastMsg != null) {
                stats.addValue(it.lastMsgTime - msgSentTime)
            } else {
                if (missingPeers.size < MaxMissingPeers) missingPeers += it
            }
        }
        return GossipStats(stats, missingPeers)
    }
}