package com.jarves.mh.cloud

import com.jarves.mh.cloud.exec.CloudExecutionClient
import com.jarves.mh.cloud.exec.CloudLogStreamer
import com.jarves.mh.cloud.exec.CloudTransportResponse
import com.jarves.mh.cloud.model.CloudWorkerSpec
import com.jarves.mh.core.result.OperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLogStreamerTest {

    private val worker = CloudWorkerSpec(
        name = "Test Worker",
        endpointUrl = "https://worker.test",
        region = "test-1",
        authKey = "secret-key"
    )

    @Test
    fun streamsFramesInOrderUntilTerminal() {
        val transport = FakeCloudTransport { req ->
            val since = req.path.substringAfter("since=").toLongOrNull() ?: 0L
            val body = if (since == 0L) {
                """{"logs":[{"seq":0,"line":"first"},{"seq":1,"line":"second"}],"nextSeq":2}"""
            } else {
                """{"logs":[],"nextSeq":2}"""
            }
            CloudTransportResponse(200, body)
        }
        val streamer = CloudLogStreamer(CloudExecutionClient(transport), pollIntervalMs = 10L)
        val received = mutableListOf<String>()
        val result = streamer.streamLogs(worker, "remote-1", onLogChunk = { received.add(it) }, isTerminal = { true })
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(listOf("first", "second"), received)
        assertEquals(2, result.framesReceived)
    }

    @Test
    fun fetchErrorFailsHonestly() {
        val transport = FakeCloudTransport {
            CloudTransportResponse(500, "server exploded")
        }
        val streamer = CloudLogStreamer(CloudExecutionClient(transport), pollIntervalMs = 10L)
        val result = streamer.streamLogs(worker, "remote-1", isTerminal = { true })
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error.orEmpty().contains("Log streaming failed"))
    }

    @Test
    fun blankAuthKeyIsNotConfigured() {
        val streamer = CloudLogStreamer(CloudExecutionClient(FakeCloudTransport()), pollIntervalMs = 10L)
        val result = streamer.streamLogs(worker.copy(authKey = ""), "remote-1", isTerminal = { true })
        assertEquals(OperationStatus.NOT_CONFIGURED, result.result.status)
    }

    @Test
    fun cancellationStopsStreaming() {
        val transport = FakeCloudTransport {
            CloudTransportResponse(200, """{"logs":[{"seq":0,"line":"x"}],"nextSeq":1}""")
        }
        val streamer = CloudLogStreamer(CloudExecutionClient(transport), pollIntervalMs = 10L)
        streamer.cancel()
        val result = streamer.streamLogs(worker, "remote-1", isTerminal = { false })
        assertTrue(result.wasCancelled)
        assertEquals(0, result.framesReceived)
    }
}
