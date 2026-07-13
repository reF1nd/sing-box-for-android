/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package io.nekohasekai.sfa.bg

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor

object DefaultNetworkListener {
    private const val TAG = "DefaultNetworkListener"

    private sealed class NetworkMessage {
        class Start(
            val key: Any,
            val listener: (Network?, LinkProperties?) -> Unit,
        ) : NetworkMessage() {
            val response = CompletableDeferred<Unit>()
        }

        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage() {
            val response = CompletableDeferred<Unit>()
        }

        class Put(val network: Network) : NetworkMessage()

        class Update(val network: Network) : NetworkMessage()

        class LinkPropertiesChanged(
            val network: Network,
            val linkProperties: LinkProperties,
        ) : NetworkMessage()

        class Lost(val network: Network) : NetworkMessage()
    }

    @OptIn(DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
    private val networkActor =
        GlobalScope.actor<NetworkMessage>(Dispatchers.Default, capacity = Channel.UNLIMITED) {
            val listeners = mutableMapOf<Any, (Network?, LinkProperties?) -> Unit>()
            var network: Network? = null
            var linkProperties: LinkProperties? = null
            val pendingRequests = arrayListOf<NetworkMessage.Get>()
            for (message in channel) {
                when (message) {
                    is NetworkMessage.Start -> {
                        try {
                            if (listeners.isEmpty()) register()
                            listeners[message.key] = message.listener
                            if (network != null) message.listener(network, linkProperties)
                            message.response.complete(Unit)
                        } catch (e: Throwable) {
                            message.response.completeExceptionally(e)
                            throw e
                        }
                    }

                    is NetworkMessage.Get -> {
                        check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
                        if (network == null) {
                            pendingRequests += message
                        } else {
                            message.response.complete(
                                network,
                            )
                        }
                    }

                    is NetworkMessage.Stop -> {
                        if (listeners.isNotEmpty() &&
                            // was not empty
                            listeners.remove(message.key) != null &&
                            listeners.isEmpty()
                        ) {
                            network = null
                            linkProperties = null
                            unregister()
                        }
                        message.response.complete(Unit)
                    }

                    is NetworkMessage.Put -> {
                        network = message.network
                        linkProperties = null
                        pendingRequests.forEach { it.response.complete(message.network) }
                        pendingRequests.clear()
                        listeners.values.forEach { it(network, null) }
                    }

                    is NetworkMessage.Update ->
                        if (network == message.network) {
                            listeners.values.forEach { it(network, linkProperties) }
                        }

                    is NetworkMessage.LinkPropertiesChanged ->
                        if (network == message.network) {
                            linkProperties = message.linkProperties
                            listeners.values.forEach { it(network, linkProperties) }
                        }

                    is NetworkMessage.Lost ->
                        if (network == message.network) {
                            network = null
                            linkProperties = null
                            listeners.values.forEach { it(null, null) }
                        }
                }
            }
        }

    suspend fun start(key: Any, listener: (Network?, LinkProperties?) -> Unit) {
        NetworkMessage.Start(key, listener).run {
            networkActor.send(this)
            response.await()
        }
    }

    suspend fun get(): Network = if (fallback) {
        @TargetApi(23)
        Application.connectivity.activeNetwork
            ?: error("missing default network") // failed to listen, return current if available
    } else {
        NetworkMessage.Get().run {
            networkActor.send(this)
            response.await()
        }
    }

    suspend fun stop(key: Any) {
        NetworkMessage.Stop(key).run {
            networkActor.send(this)
            response.await()
        }
    }

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            enqueue(NetworkMessage.Put(network))
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // it's a good idea to refresh capabilities
            enqueue(NetworkMessage.Update(network))
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            enqueue(NetworkMessage.LinkPropertiesChanged(network, linkProperties))
        }

        override fun onLost(network: Network) {
            enqueue(NetworkMessage.Lost(network))
        }
    }

    @Volatile
    private var fallback = false
    private val request =
        NetworkRequest.Builder().apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            if (Build.VERSION.SDK_INT == 23) { // workarounds for OEM bugs
                removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            }
        }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        when (Build.VERSION.SDK_INT) {
            in 31..Int.MAX_VALUE ->
                @TargetApi(31)
                {
                    Application.connectivity.registerBestMatchingNetworkCallback(
                        request,
                        Callback,
                        mainHandler,
                    )
                }

            in 28 until 31 ->
                @TargetApi(28)
                { // we want REQUEST here instead of LISTEN
                    Application.connectivity.requestNetwork(request, Callback, mainHandler)
                }

            in 26 until 28 ->
                @TargetApi(26)
                {
                    Application.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)
                }

            in 24 until 26 ->
                @TargetApi(24)
                {
                    Application.connectivity.registerDefaultNetworkCallback(Callback)
                }

            else ->
                try {
                    fallback = false
                    Application.connectivity.requestNetwork(request, Callback)
                } catch (e: RuntimeException) {
                    fallback =
                        true // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
        }
    }

    private fun unregister() {
        runCatching {
            Application.connectivity.unregisterNetworkCallback(Callback)
        }
    }

    private fun enqueue(message: NetworkMessage) {
        val result = networkActor.trySend(message)
        if (result.isFailure) {
            Log.w(TAG, "failed to enqueue network update", result.exceptionOrNull())
        }
    }
}
