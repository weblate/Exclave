/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.hysteria2

import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.isIpv6Address
import io.nekohasekai.sagernet.ktx.isValidHysteriaMultiPort
import io.nekohasekai.sagernet.ktx.isValidHysteriaPort
import io.nekohasekai.sagernet.ktx.joinHostPort
import io.nekohasekai.sagernet.ktx.queryParameter
import io.nekohasekai.sagernet.ktx.urlSafe
import libcore.Libcore
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File


fun parseHysteria2(rawURL: String): Hysteria2Bean {
    var url = rawURL

    // fuck port hopping URL
    val hostPort = url.substringAfter("://").substringAfter("@")
        .substringBefore("?").substringBefore("/")
    var port = ""
    if (!hostPort.endsWith("]") && hostPort.lastIndexOf(":") > 0) {
        port = hostPort.substringAfterLast(":")
    }
    if (port.isNotEmpty() && port.isValidHysteriaMultiPort()) {
        url = url.replace(":$port", ":0")
    }

    val link = Libcore.parseURL(url)
    return Hysteria2Bean().apply {
        name = link.fragment

        serverAddress = link.host
        serverPorts = if (port.isNotEmpty() && port.isValidHysteriaMultiPort()) {
            port
        } else if (link.port > 0) {
            link.port.toString()
        } else {
            "443"
        }

        link.queryParameter("mport")?.also {
            serverPorts = it
        }

        if (link.username.isNotBlank()) {
            auth = link.username
        }

        if (link.password.isNotBlank()) {
            auth += ":" + link.password
        }

        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1"
        }
        link.queryParameter("pinSHA256")?.also {
            pinSHA256 = it
        }
        link.queryParameter("obfs")?.also { it ->
            if (it == "salamander") {
                link.queryParameter("obfs-password")?.also {
                    obfs = it
                }
            }
        }
    }
}

fun Hysteria2Bean.toUri(): String {
    if (!serverPorts.isValidHysteriaPort()) {
        error("invalid port: $serverPorts")
    }
    val builder = Libcore.newURL("hysteria2")
    builder.host = serverAddress
    builder.port = if (serverPorts.isValidHysteriaMultiPort()) {
        0 // placeholder
    } else {
        serverPorts.toInt()
    }

    if (auth.isNotBlank()) {
        val a = auth.split(":")
        if (a.size == 2) {
            // https://github.com/apernet/hysteria/blob/c7545cc870e5cc62a187ad03a083920e6bef049f/app/cmd/client.go#L308-L316
            builder.username = a[0]
            builder.password = a[1]
        } else {
            builder.username = auth
        }
    }

    if (sni.isNotBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (pinSHA256.isNotBlank()) {
        builder.addQueryParameter("pinSHA256", pinSHA256)
    }
    if (obfs.isNotBlank()) {
        builder.addQueryParameter("obfs", "salamander")
        builder.addQueryParameter("obfs-password", obfs)
    }
    if (name.isNotBlank()) {
        builder.setRawFragment(name.urlSafe())
    }
    builder.rawPath = "/"
    val url = builder.string
    if (serverPorts.isValidHysteriaMultiPort()) {
        // fuck port hopping URL
        val port = url.substringAfter("://").substringAfter("@")
            .substringBefore("?").substringBefore("/")
            .substringAfterLast(":")
        return url.replace(":$port/", ":$serverPorts/")
    }
    return url
}

fun Hysteria2Bean.buildHysteria2Config(port: Int, cacheFile: (() -> File)?): String {
    if (!serverPorts.isValidHysteriaPort()) {
        error("invalid port: $serverPorts")
    }
    val usePortHopping = DataStore.hysteriaEnablePortHopping && serverPorts.isValidHysteriaMultiPort()
    if (usePortHopping && DataStore.tunImplementation == TunImplementation.SYSTEM) {
        // NOT a TODO: system stack need VpnService protect to work
        error("Please switch to TUN gVisor stack for Hysteria 2 port hopping.")
    }

    val hostPort = if (usePortHopping) {
        // Hysteria 2 port hopping is incompatible with chain proxy
        if (serverAddress.isIpv6Address()) {
            "[$serverAddress]:$serverPorts"
        } else {
            "$serverAddress:$serverPorts"
        }
    } else {
        joinHostPort(finalAddress, finalPort)
    }

    val confObject: MutableMap<String, Any> = HashMap()
    confObject["server"] = hostPort
    if (auth.isNotBlank()) {
        confObject["auth"] = auth
    }

    val tlsObject: MutableMap<String, Any> = HashMap()
    if (allowInsecure) {
        tlsObject["insecure"] = true
    }
    if (!usePortHopping) {
        if (sni.isBlank() && !serverAddress.isIpAddress()) {
            sni = serverAddress
        }
    }
    if (sni.isNotBlank()) {
        tlsObject["sni"] = sni
    }
    if (caText.isNotBlank() && cacheFile != null) {
        val caFile = cacheFile()
        caFile.writeText(caText)
        tlsObject["ca"] = caFile.absolutePath
    }
    if (pinSHA256.isNotBlank()) {
        tlsObject["pinSHA256"] = pinSHA256
    }
    if (tlsObject.isNotEmpty()) {
        confObject["tls"] = tlsObject
    }

    val transportObject: MutableMap<String, Any> = HashMap()
    transportObject["type"] = "udp"
    if (DataStore.hysteriaEnablePortHopping && serverPorts.isValidHysteriaMultiPort()) {
        val udpObject: MutableMap<String, Any> = HashMap()
        udpObject["hopInterval"] = "$hopInterval" + "s"
        transportObject["udp"] = udpObject
    }
    confObject["transport"] = transportObject

    if (obfs.isNotBlank()) {
        val obfsObject: MutableMap<String, Any> = HashMap()
        obfsObject["type"] = "salamander"
        val salamanderObject: MutableMap<String, Any> = HashMap()
        salamanderObject["password"] = obfs
        obfsObject["salamander"] = salamanderObject
        confObject["obfs"] = obfsObject
    }

    val quicObject: MutableMap<String, Any> = HashMap()
    if (disableMtuDiscovery) {
        quicObject["disableMtuDiscovery"] = true
    }
    if (initStreamReceiveWindow > 0) {
        quicObject["initStreamReceiveWindow"] = initStreamReceiveWindow
    }
    if (maxStreamReceiveWindow > 0) {
        quicObject["maxStreamReceiveWindow"] = maxStreamReceiveWindow
    }
    if (initConnReceiveWindow > 0) {
        quicObject["initConnReceiveWindow"] = initConnReceiveWindow
    }
    if (maxConnReceiveWindow > 0) {
        quicObject["maxConnReceiveWindow"] = maxConnReceiveWindow
    }
    if (quicObject.isNotEmpty()) {
        confObject["quic"] = quicObject
    }

    val bandwidthObject: MutableMap<String, Any> = HashMap()
    if (uploadMbps > 0) {
        bandwidthObject["up"] = "$uploadMbps mbps"
    }
    if (downloadMbps > 0) {
        bandwidthObject["down"] = "$downloadMbps mbps"
    }
    if (bandwidthObject.isNotEmpty()) {
        confObject["bandwidth"] = bandwidthObject
    }

    val socks5Object: MutableMap<String, Any> = HashMap()
    socks5Object["listen"] = joinHostPort(LOCALHOST, port)
    confObject["socks5"] = socks5Object

    confObject["lazy"] = true
    confObject["fastOpen"] = true

    val options = DumperOptions()
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.isPrettyFlow = true;
    val yaml = Yaml(options)
    return yaml.dump(confObject)
}
