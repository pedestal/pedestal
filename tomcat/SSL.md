# Tomcat 10 and SSL

Notes on getting Tomcat SSL working for Tomcat 10.

[Docs](https://tomcat.apache.org/tomcat-10.1-doc/config/http.html#SSL_Support)

## No SSLHostConfig element was found with the hostName [_default_] to match the defaultSSLHostConfigName for the connector [https-jsse-nio-14342]

## SSLHostConfig attribute certificateFile must be defined when using an SSL connector

- Message is confusing
- certificate.getCertificateFile() is nil in org.apache.tomcat.util.net.SSLUtilBase.getKeyManagers()
- org.apache.tomcat.util.net.SSLHostConfigCertificate


## NPE inside protocolHandler.init()


## Cannot store non-PrivateKeys

