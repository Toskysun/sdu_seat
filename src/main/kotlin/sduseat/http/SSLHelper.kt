/*
 * Copyright (C) 2025-2026 Toskysun
 * 
 * This file is part of Sdu-Seat
 * Sdu-Seat is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Sdu-Seat is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Sdu-Seat.  If not, see <https://www.gnu.org/licenses/>.
 */

package sduseat.http

import io.github.oshai.kotlinlogging.KotlinLogging as KLogger
import java.io.IOException
import java.io.InputStream
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

@Suppress("unused")
object SSLHelper {
    private val logger = KLogger.logger {}

    /**
     * 为了解决客户端不信任服务器数字证书的问题。
     * 网络上大部分的解决方案都是让客户端不对证书做任何检查，
     * 这是一种有很大安全漏洞的办法。
     * 这里我们提供了一个更安全的实现。
     */
    val unsafeTrustManager: X509TrustManager =
        object : X509TrustManager {
            @Throws(CertificateException::class)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                logger.warn { "Skipping client certificate validation" }
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    chain[0].checkValidity()
                } catch (e: Exception) {
                    throw CertificateException("Certificate not valid or trusted.")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return arrayOf()
            }
        }

    val unsafeSSLSocketFactory: SSLSocketFactory by lazy {
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(unsafeTrustManager), SecureRandom())
            sslContext.socketFactory as SSLSocketFactory
        } catch (e: Exception) {
            logger.error(e) { "Error creating SSLSocketFactory" }
            throw RuntimeException(e)
        }
    }

    /**
     * 此类是用于主机名验证的基接口。在握手期间，如果 URL 的主机名和服务器的标识主机名不匹配，
     * 则验证机制可以回调此接口的实现程序来确定是否应该允许此连接。策略可以是基于证书的或依赖于其他验证方案。
     * 当验证 URL 主机名使用的默认规则失败时使用这些回调。如果主机名是可接受的，则返回 true
     */
    val unsafeHostnameVerifier: HostnameVerifier = HostnameVerifier { hostname, _ ->
        logger.warn { "Skipping hostname verification for: $hostname" }
        true
    }

    class SSLParams {
        lateinit var sSLSocketFactory: SSLSocketFactory
        lateinit var trustManager: X509TrustManager
    }

    /**
     * https单向认证
     * 可以额外配置信任服务端的证书策略，否则默认是按CA证书去验证的，若不是CA可信任的证书，则无法通过验证
     */
    fun getSslSocketFactory(trustManager: X509TrustManager): SSLParams? {
        return getSslSocketFactoryBase(trustManager, null, null)
    }

    /**
     * https单向认证
     * 用含有服务端公钥的证书校验服务端证书
     */
    fun getSslSocketFactory(vararg certificates: InputStream): SSLParams? {
        return getSslSocketFactoryBase(null, null, null, *certificates)
    }

    /**
     * https双向认证
     * bksFile 和 password -> 客户端使用bks证书校验服务端证书
     * certificates -> 用含有服务端公钥的证书校验服务端证书
     */
    fun getSslSocketFactory(
        bksFile: InputStream,
        password: String,
        vararg certificates: InputStream
    ): SSLParams? {
        return getSslSocketFactoryBase(null, bksFile, password, *certificates)
    }

    /**
     * https双向认证
     * bksFile 和 password -> 客户端使用bks证书校验服务端证书
     * X509TrustManager -> 如果需要自己校验，那么可以自己实现相关校验，如果不需要自己校验，那么传null即可
     */
    fun getSslSocketFactory(
        bksFile: InputStream,
        password: String,
        trustManager: X509TrustManager
    ): SSLParams? {
        return getSslSocketFactoryBase(trustManager, bksFile, password)
    }

    private fun getSslSocketFactoryBase(
        trustManager: X509TrustManager?,
        bksFile: InputStream?,
        password: String?,
        vararg certificates: InputStream
    ): SSLParams? {
        val sslParams = SSLParams()
        try {
            val keyManagers = prepareKeyManager(bksFile, password)
            val trustManagers = prepareTrustManager(*certificates)
            val manager: X509TrustManager = trustManager ?: chooseTrustManager(trustManagers)
            // 创建TLS类型的SSLContext对象，that uses our TrustManager
            val sslContext = SSLContext.getInstance("TLS")
            // 用上面得到的trustManagers初始化SSLContext，这样sslContext就会信任keyStore中的证书
            // 第一个参数是授权的密钥管理器，用来授权验证，比如授权自签名的证书验证。第二个是被授权的证书管理器，用来验证服务器端的证书
            sslContext.init(keyManagers, arrayOf<TrustManager>(manager), null)
            // 通过sslContext获取SSLSocketFactory对象
            sslParams.sSLSocketFactory = sslContext.socketFactory
            sslParams.trustManager = manager
            return sslParams
        } catch (e: NoSuchAlgorithmException) {
            logger.error(e) {}
        } catch (e: KeyManagementException) {
            logger.error(e) {}
        }
        return null
    }

    private fun prepareKeyManager(bksFile: InputStream?, password: String?): Array<KeyManager>? {
        try {
            if (bksFile == null || password == null) return null
            val clientKeyStore = KeyStore.getInstance("BKS")
            clientKeyStore.load(bksFile, password.toCharArray())
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(clientKeyStore, password.toCharArray())
            return kmf.keyManagers
        } catch (e: Exception) {
            logger.error(e) {}
        }
        return null
    }

    private fun prepareTrustManager(vararg certificates: InputStream): Array<TrustManager> {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        // 创建一个默认类型的KeyStore，存储我们信任的证书
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)
        for ((index, certStream) in certificates.withIndex()) {
            val certificateAlias = index.toString()
            // 证书工厂根据证书文件的流生成证书 cert
            val cert = certificateFactory.generateCertificate(certStream)
            // 将 cert 作为可信证书放入到keyStore中
            keyStore.setCertificateEntry(certificateAlias, cert)
            try {
                certStream.close()
            } catch (e: IOException) {
                logger.error(e) {}
            }
        }
        //我们创建一个默认类型的TrustManagerFactory
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        //用我们之前的keyStore实例初始化TrustManagerFactory，这样tmf就会信任keyStore中的证书
        tmf.init(keyStore)
        //通过tmf获取TrustManager数组，TrustManager也会信任keyStore中的证书
        return tmf.trustManagers
    }

    private fun chooseTrustManager(trustManagers: Array<TrustManager>): X509TrustManager {
        for (trustManager in trustManagers) {
            if (trustManager is X509TrustManager) {
                return trustManager
            }
        }
        throw NullPointerException()
    }
}
