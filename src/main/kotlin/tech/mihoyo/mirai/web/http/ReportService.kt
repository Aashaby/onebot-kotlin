package tech.mihoyo.mirai.web.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.mamoe.mirai.event.Listener
import net.mamoe.mirai.event.events.BotEvent
import net.mamoe.mirai.event.subscribeAlways
import net.mamoe.mirai.utils.currentTimeSeconds
import tech.mihoyo.mirai.BotSession
import tech.mihoyo.mirai.MiraiApi
import tech.mihoyo.mirai.data.common.*
import tech.mihoyo.mirai.util.EventFilter
import tech.mihoyo.mirai.util.logger
import tech.mihoyo.mirai.util.toJson
import tech.mihoyo.mirai.web.HeartbeatScope
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class ReportServiceScope(coroutineContext: CoroutineContext) : CoroutineScope {
    override val coroutineContext: CoroutineContext = coroutineContext + CoroutineExceptionHandler { _, throwable ->
        logger.error("Exception in ReportService", throwable)
    } + SupervisorJob()
}


class ReportService(
    private val session: BotSession
) {

    private val http = HttpClient(OkHttp) {
        engine {
            config {
                retryOnConnectionFailure(true)
            }
        }
    }

    private var sha1Util: Mac? = null

    private var subscription: Listener<BotEvent>? = null

    private var heartbeatJob: Job? = null

    private val scope = ReportServiceScope(EmptyCoroutineContext)

    private val settings = session.settings.http

    init {
        scope.launch {
            startReportService()
        }
    }

    private suspend fun startReportService() {
        if (settings.postUrl != "") {
            if (settings.secret != "") {
                val mac = Mac.getInstance("HmacSHA1")
                val secret = SecretKeySpec(settings.secret.toByteArray(), "HmacSHA1")
                mac.init(secret)
                sha1Util = mac
            }

            report(
                session.cqApiImpl,
                settings.postUrl!!,
                session.bot.id,
                CQLifecycleMetaEventDTO(session.botId, "enable", currentTimeSeconds).toJson(),
                settings.secret,
                false
            )

            subscription = session.bot.subscribeAlways {
                this.toCQDTO(isRawMessage = settings.postMessageFormat == "string")
                    .takeIf { it !is CQIgnoreEventDTO }?.apply {
                        val jsonToSend = this.toJson()
                        logger.debug("HTTP Report将要发送事件: $jsonToSend")
                        if (!EventFilter.eval(jsonToSend)) {
                            logger.debug("事件被Event Filter命中, 取消发送")
                        } else {
                            scope.launch(Dispatchers.IO) {
                                report(
                                    session.cqApiImpl,
                                    settings.postUrl!!,
                                    bot.id,
                                    jsonToSend,
                                    settings.secret,
                                    true
                                )
                            }
                        }
                    }
            }

            if (session.settings.heartbeat.enable) {
                heartbeatJob = HeartbeatScope(EmptyCoroutineContext).launch {
                    while (true) {
                        report(
                            session.cqApiImpl,
                            settings.postUrl!!,
                            session.bot.id,
                            CQHeartbeatMetaEventDTO(
                                session.botId,
                                currentTimeSeconds,
                                CQPluginStatusData(
                                    good = session.bot.isOnline,
                                    online = session.bot.isOnline
                                ),
                                session.settings.heartbeat.interval
                            ).toJson(),
                            settings.secret,
                            false
                        )
                        delay(session.settings.heartbeat.interval)
                    }
                }
            }
        }
    }

    private suspend fun report(
        miraiApi: MiraiApi,
        url: String,
        botId: Long,
        json: String,
        secret: String,
        shouldHandleOperation: Boolean
    ) {
        val res = http.request<String?> {
            url(url)
            headers {
                append("User-Agent", "CQHttp/4.15.0")
                append("X-Self-ID", botId.toString())
                secret.takeIf { it != "" }?.apply {
                    append("X-Signature", getSha1Hash(json))
                }
            }
            method = HttpMethod.Post
            body = TextContent(json, ContentType.Application.Json.withParameter("charset", "utf-8"))
        }
        if (res != "") logger.debug("收到上报响应  $res")
        if (shouldHandleOperation && res != null && res != "") {
            try {
                val respJson = Json.parseToJsonElement(res).jsonObject
                val sentJson = Json.parseToJsonElement(json).jsonObject
                val params = hashMapOf("context" to sentJson, "operation" to respJson)
                miraiApi.cqHandleQuickOperation(params)
            } catch (e: SerializationException) {
                logger.error("解析HTTP上报返回数据成json失败")
            }
        }
    }

    private fun getSha1Hash(content: String): String {
        sha1Util?.apply {
            return "sha1=" + this.doFinal(content.toByteArray()).fold("", { str, it -> str + "%02x".format(it) })
        }
        return ""
    }

    suspend fun close() {
        if (settings.postUrl != "") {
            report(
                session.cqApiImpl,
                settings.postUrl!!,
                session.bot.id,
                CQLifecycleMetaEventDTO(session.botId, "disable", currentTimeSeconds).toJson(),
                settings.secret,
                false
            )
        }
        http.close()
        heartbeatJob?.cancel()
        subscription?.complete()
    }
}