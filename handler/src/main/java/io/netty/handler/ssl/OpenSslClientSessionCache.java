/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.ssl;

import javax.net.ssl.SSLException;
import java.util.HashMap;
import java.util.Map;

final class OpenSslClientSessionCache extends OpenSslSessionCache {
    // TODO: Should we support to have a List of OpenSslSessions for a Host/Port key and so be able to
    // support sessions for different protocols / ciphers to the same remote peer ?
    private final Map<HostPort, OpenSslSession> sessions = new HashMap<HostPort, OpenSslSession>();

    OpenSslClientSessionCache(OpenSslEngineMap engineMap) {
        super(engineMap);
    }

    @Override
    protected boolean sessionCreated(OpenSslSession session) {
        assert Thread.holdsLock(this);
        String host = session.getPeerHost();
        int port = session.getPeerPort();
        if (host == null || port == -1) {
            return false;
        }
        HostPort hostPort = new HostPort(host, port);
        if (sessions.containsKey(hostPort)) {
            return false;
        }
        sessions.put(hostPort, session);
        return true;
    }

    @Override
    protected void sessionRemoved(OpenSslSession session) {
        assert Thread.holdsLock(this);
        String host = session.getPeerHost();
        int port = session.getPeerPort();
        if (host == null || port == -1) {
            return;
        }
        sessions.remove(new HostPort(host, port));
    }

    private static boolean isProtocolEnabled(OpenSslSession session, String[] enabledProtocols) {
        return arrayContains(session.getProtocol(), enabledProtocols);
    }

    private static boolean isCipherSuiteEnabled(OpenSslSession session, String[] enabledCipherSuites) {
        return arrayContains(session.getCipherSuite(), enabledCipherSuites);
    }

    private static boolean arrayContains(String expected, String[] array) {
        for (int i = 0; i < array.length; ++i) {
            String value = array[i];
            if (value.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    void setSession(ReferenceCountedOpenSslEngine engine) throws SSLException {
        String host = engine.getPeerHost();
        int port = engine.getPeerPort();
        if (host == null || port == -1) {
            return;
        }
        HostPort hostPort = new HostPort(host, port);
        synchronized (this) {
            OpenSslSession session = sessions.get(hostPort);

            if (session == null) {
                return;
            }
            if (!session.isValid()) {
                removeInvalidSession(session);
                return;
            }

            // Ensure the protocol and ciphersuite can be used.
            if (!isProtocolEnabled(session, engine.getEnabledProtocols()) ||
                    !isCipherSuiteEnabled(session, engine.getEnabledCipherSuites())) {
                return;
            }
            engine.setSession(session);
        }
    }

    private static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public int hashCode() {
            int result = host.hashCode();
            result = 31 * result + port;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof HostPort)) {
                return false;
            }
            HostPort other = (HostPort) obj;
            return port == other.port && host.equalsIgnoreCase(other.host);
        }
    }
}