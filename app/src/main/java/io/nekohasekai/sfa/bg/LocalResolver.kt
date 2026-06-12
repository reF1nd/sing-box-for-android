package io.nekohasekai.sfa.bg

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

object LocalResolver : LocalDNSTransport {
    private const val RCODE_NXDOMAIN = 3
    private const val DNS_QUERY_TIMEOUT_MILLIS = 10_000L

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val defaultNetwork = DefaultNetworkMonitor.defaultNetwork ?: error("missing default interface")
        return runBlocking {
            awaitDNS(ctx) { signal, completion ->
                val callback =
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            completion.complete {
                                if (rcode == 0) {
                                    ctx.rawSuccess(answer)
                                } else {
                                    ctx.errorCode(rcode)
                                }
                            }
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            when (val cause = error.cause) {
                                is ErrnoException -> {
                                    completion.complete {
                                        ctx.errnoCode(cause.errno)
                                    }
                                    return
                                }
                            }
                            completion.completeExceptionally(error)
                        }
                    }
                DnsResolver.getInstance().rawQuery(
                    defaultNetwork,
                    message,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback,
                )
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val defaultNetwork = DefaultNetworkMonitor.defaultNetwork ?: error("missing default interface")
        return runBlocking {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                awaitDNS(ctx) { signal, completion ->
                    val callback =
                        object : DnsResolver.Callback<Collection<InetAddress>> {
                            @Suppress("ThrowableNotThrown")
                            override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                                completion.complete {
                                    if (rcode == 0) {
                                        ctx.success(
                                            (answer as Collection<InetAddress?>).mapNotNull { it?.hostAddress }
                                                .joinToString("\n"),
                                        )
                                    } else {
                                        ctx.errorCode(rcode)
                                    }
                                }
                            }

                            override fun onError(error: DnsResolver.DnsException) {
                                when (val cause = error.cause) {
                                    is ErrnoException -> {
                                        completion.complete {
                                            ctx.errnoCode(cause.errno)
                                        }
                                        return
                                    }
                                }
                                completion.completeExceptionally(error)
                            }
                        }
                    val type =
                        when {
                            network.endsWith("4") -> DnsResolver.TYPE_A
                            network.endsWith("6") -> DnsResolver.TYPE_AAAA
                            else -> null
                        }
                    if (type != null) {
                        DnsResolver.getInstance().query(
                            defaultNetwork,
                            domain,
                            type,
                            DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(),
                            signal,
                            callback,
                        )
                    } else {
                        DnsResolver.getInstance().query(
                            defaultNetwork,
                            domain,
                            DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(),
                            signal,
                            callback,
                        )
                    }
                }
            } else {
                val answer =
                    try {
                        defaultNetwork.getAllByName(domain)
                    } catch (e: UnknownHostException) {
                        ctx.errorCode(RCODE_NXDOMAIN)
                        return@runBlocking
                    }
                ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
            }
        }
    }

    private suspend fun awaitDNS(
        ctx: ExchangeContext,
        query: (CancellationSignal, DNSQueryCompletion) -> Unit,
    ) {
        val signal = CancellationSignal()
        val completion = DNSQueryCompletion()
        ctx.onCancel {
            signal.cancel()
            completion.completeExceptionally(CancellationException("DNS query canceled"))
        }
        try {
            query(signal, completion)
            withTimeout(DNS_QUERY_TIMEOUT_MILLIS) {
                completion.await()
            }
        } catch (e: Throwable) {
            signal.cancel()
            completion.completeExceptionally(e)
            throw e
        }
    }

    private class DNSQueryCompletion {
        private val completed = AtomicBoolean(false)
        private val result = CompletableDeferred<Unit>()

        suspend fun await() {
            result.await()
        }

        fun complete(action: () -> Unit) {
            if (!completed.compareAndSet(false, true)) return
            try {
                action()
                result.complete(Unit)
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }

        fun completeExceptionally(error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                result.completeExceptionally(error)
            }
        }
    }
}
