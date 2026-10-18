package systems.diath.homeclaim.api.http

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import com.aayushatharva.brotli4j.encoder.Encoder
import com.github.luben.zstd.ZstdOutputStream
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.CompressionEncoder
import io.ktor.server.plugins.compression.condition
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext

const val DYNAMIC_COMPRESSION_MIN_BYTES: Long = 1024

private val CompressionOperation = AttributeKey<Boolean>("HomeClaimHttpCompressionResponse")
private val VaryAcceptEncoding = createApplicationPlugin("HomeClaimVaryAcceptEncoding") {
    onCallRespond { call, _ -> call.ensureVaryAcceptEncoding() }
}

/** Installs only HomeClaim's zstd/br response codecs; gzip is deliberately absent. */
fun Application.installHomeClaimHttpCompression() {
    Brotli4jLoader.ensureAvailability()
    install(Compression) {
        condition { content ->
            val length = content.contentLength
            val type = content.contentType ?: response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
            (length == null || length >= DYNAMIC_COMPRESSION_MIN_BYTES) &&
                type != null && isCompressibleText(type) &&
                content.headers[HttpHeaders.ContentEncoding] == null &&
                response.headers[HttpHeaders.ContentEncoding] == null &&
                CompressionOperation !in attributes
        }
        encoder("zstd", ZstdHttpEncoder) {
            priority = 2.0
            condition { negotiatedDynamicEncoding() == "zstd" }
        }
        encoder("br", BrotliHttpEncoder) {
            priority = 1.0
            condition { negotiatedDynamicEncoding() == "br" }
        }
    }
    install(VaryAcceptEncoding)

    intercept(ApplicationCallPipeline.Call) {
        val negotiation = AcceptEncodingNegotiation.parse(call.request.headers.getAll(HttpHeaders.AcceptEncoding))
        val selected = negotiation.select(ContentEncodingPolicy.DYNAMIC)
        if (selected == null) {
            call.attributes.put(CompressionOperation, true)
            call.respond(HttpStatusCode.NotAcceptable)
            finish()
            return@intercept
        }
        proceed()
    }
}

private fun io.ktor.server.application.ApplicationCall.negotiatedDynamicEncoding(): String? =
    AcceptEncodingNegotiation.parse(request.headers.getAll(HttpHeaders.AcceptEncoding))
        .select(ContentEncodingPolicy.DYNAMIC)
        ?.takeUnless { it == "identity" }

private fun io.ktor.server.application.ApplicationCall.ensureVaryAcceptEncoding() {
    val varyValues = response.headers.values(HttpHeaders.Vary)
        .flatMap { it.split(',') }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
    if (varyValues.none { it.equals(HttpHeaders.AcceptEncoding, ignoreCase = true) }) {
        response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
    }
}

private fun isCompressibleText(type: ContentType): Boolean =
    type.contentType.equals("text", ignoreCase = true) ||
        type.match(ContentType.Application.Json) ||
        type.match(ContentType.Application.JavaScript) ||
        type.match(ContentType.Application.Xml) ||
        type.match(ContentType.Application.FormUrlEncoded)

private object ZstdHttpEncoder : CompressionEncoder {
    override fun predictCompressedLength(originalLength: Long): Long? = null

    override fun compress(readChannel: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        CoroutineScope(coroutineContext).writerOnIo { destination ->
            ZstdOutputStream(destination, 3).use { encoded ->
                readChannel.toInputStream().use { input -> input.copyTo(encoded, 32 * 1024) }
            }
        }

    override fun compress(writeChannel: ByteWriteChannel, coroutineContext: CoroutineContext): ByteWriteChannel =
        pipeThrough(writeChannel, coroutineContext) { destination -> ZstdOutputStream(destination, 3) }
}

private object BrotliHttpEncoder : CompressionEncoder {
    override fun predictCompressedLength(originalLength: Long): Long? = null

    override fun compress(readChannel: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        CoroutineScope(coroutineContext).writerOnIo { destination ->
            BrotliOutputStream(destination, Encoder.Parameters.create(4)).use { encoded ->
                readChannel.toInputStream().use { input -> input.copyTo(encoded, 32 * 1024) }
            }
        }

    override fun compress(writeChannel: ByteWriteChannel, coroutineContext: CoroutineContext): ByteWriteChannel =
        pipeThrough(writeChannel, coroutineContext) { destination -> BrotliOutputStream(destination, Encoder.Parameters.create(4)) }
}

private fun CoroutineScope.writerOnIo(write: suspend (OutputStream) -> Unit): ByteReadChannel =
    io.ktor.utils.io.writer(Dispatchers.IO) {
        val stream = channel.toOutputStream(coroutineContext[Job])
        write(stream)
    }.channel

private fun pipeThrough(
    destination: ByteWriteChannel,
    context: CoroutineContext,
    encoder: (OutputStream) -> OutputStream
): ByteWriteChannel {
    val plain = ByteChannel()
    CoroutineScope(context).launch(Dispatchers.IO) {
        try {
            encoder(destination.toOutputStream(coroutineContext[Job])).use { compressed ->
                plain.toInputStream().use { input -> input.copyTo(compressed, 32 * 1024) }
            }
        } catch (failure: Throwable) {
            plain.close(failure)
            destination.close(failure)
        }
    }
    return plain
}
