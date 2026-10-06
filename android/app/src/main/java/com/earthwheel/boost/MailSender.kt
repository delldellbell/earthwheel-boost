package com.earthwheel.boost

import android.util.Base64
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Trimite emailul de recuperare direct din aplicație prin Gmail (SMTPS, port 465, TLS)
 * cu un cont Gmail + "parolă de aplicație". Fără biblioteci externe.
 * Se apelează doar de pe un thread de fundal.
 */
object MailSender {
    private const val HOST = "smtp.gmail.com"
    private const val PORT = 465

    fun send(user: String, appPassword: String, to: String, subject: String, body: String) {
        require(listOf(user, to).none { it.contains('\r') || it.contains('\n') || it.contains('<') || it.contains('>') }) { "adresă invalidă" }

        val sock = SSLSocketFactory.getDefault().createSocket(HOST, PORT) as SSLSocket
        sock.use { s ->
            s.soTimeout = 20_000
            s.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, s.session)) {
                throw IOException("certificat TLS invalid pentru $HOST")
            }
            val input = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            val out = s.outputStream

            expect(input, 220)
            cmd(out, "EHLO earthwheel-boost"); expect(input, 250)
            cmd(out, "AUTH LOGIN"); expect(input, 334)
            cmd(out, b64(user)); expect(input, 334)
            cmd(out, b64(appPassword)); expect(input, 235)
            cmd(out, "MAIL FROM:<$user>"); expect(input, 250)
            cmd(out, "RCPT TO:<$to>"); expect(input, 250, 251)
            cmd(out, "DATA"); expect(input, 354)

            val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
            val msg = StringBuilder()
                .append("From: =?UTF-8?B?").append(b64("Earthwheel Boost")).append("?= <").append(user).append(">\r\n")
                .append("To: <").append(to).append(">\r\n")
                .append("Subject: =?UTF-8?B?").append(b64(subject)).append("?=\r\n")
                .append("Date: ").append(date).append("\r\n")
                .append("MIME-Version: 1.0\r\n")
                .append("Content-Type: text/plain; charset=UTF-8\r\n")
                .append("Content-Transfer-Encoding: base64\r\n\r\n")
                .append(Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.CRLF))
                .append("\r\n.\r\n")
            out.write(msg.toString().toByteArray(Charsets.US_ASCII)); out.flush()
            expect(input, 250)
            cmd(out, "QUIT")
        }
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun cmd(out: OutputStream, line: String) {
        out.write((line + "\r\n").toByteArray(Charsets.UTF_8)); out.flush()
    }

    /** citește răspunsul (posibil pe mai multe linii) și verifică codul */
    private fun expect(input: BufferedReader, vararg codes: Int) {
        var line: String
        do {
            line = input.readLine() ?: throw IOException("conexiune închisă de server")
        } while (line.length > 3 && line[3] == '-')
        val code = line.take(3).toIntOrNull() ?: throw IOException("răspuns invalid: $line")
        if (code !in codes) throw IOException("SMTP $code: ${line.drop(4)}")
    }
}
