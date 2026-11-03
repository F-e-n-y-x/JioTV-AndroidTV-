package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class ServerDiscoveryTest {

    @Test
    fun toServer_buildsUrlFromProbedIpAndMainPort() {
        assertEquals(ServerDiscovery.Server("Living room", "http://192.168.1.5:8080", lite = false),
            ServerDiscovery.toServer("192.168.1.5", "jtv-server", "full", " Living room ", 8080, false))
        assertEquals(ServerDiscovery.Server("192.168.1.6", "https://192.168.1.6:29180", lite = true),
            ServerDiscovery.toServer("192.168.1.6", "jtv-server", "lite", "", 29180, true))
    }

    @Test
    fun toServer_rejectsOtherAppsAndBadPorts() {
        assertNull(ServerDiscovery.toServer("192.168.1.5", "something", "full", "x", 8080, false))
        assertNull(ServerDiscovery.toServer("192.168.1.5", "jtv-server", "full", "x", 0, false))
        assertNull(ServerDiscovery.toServer("192.168.1.5", "jtv-server", "full", "x", 70000, false))
    }

    @Test
    fun hostsIn24_coversTheSubnetExceptSelf() {
        val hosts = ServerDiscovery.hostsIn24(InetAddress.getByName("10.0.2.16") as Inet4Address)
        assertEquals(253, hosts.size)
        assertEquals("10.0.2.1", hosts.first())
        assertEquals("10.0.2.254", hosts.last())
        assert("10.0.2.16" !in hosts)
    }

    @Test
    fun merge_dedupesByUrl() {
        val a = ServerDiscovery.Server("A", "http://10.0.0.2:8080", false)
        val list = ServerDiscovery.merge(emptyList(), a)
        assertSame(list, ServerDiscovery.merge(list, a.copy(name = "A from NSD")))
        assertEquals(2, ServerDiscovery.merge(list, a.copy(url = "http://10.0.0.3:8080")).size)
    }
}
