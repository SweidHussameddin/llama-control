package de.sweid.llamactl.service;

import de.sweid.llamactl.model.ModelDetails;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Parses just enough of a GGUF file's metadata block to extract the fields the launcher form
 * needs to set sensible slider maxes — architecture, context length, block (layer) count, head
 * count.
 *
 * <p>Reads the first 32 MB of the file via {@link FileChannel#map}; that's more than enough to
 * cover even very long tokenizer arrays for any production GGUF. Results are cached per path
 * keyed by {@code (path, mtime)}; the file is re-parsed if the mtime changes.
 *
 * <p>References:
 * <a href="https://github.com/ggerganov/ggml/blob/master/docs/gguf.md">GGUF format spec</a>.
 */
@Service
public class GgufReader {

  private static final Logger LOG = LoggerFactory.getLogger(GgufReader.class);
  private static final byte[] MAGIC = "GGUF".getBytes(StandardCharsets.US_ASCII);
  private static final long MAP_BYTES = 32L * 1024L * 1024L;
  private static final long MAX_STRING_BYTES = 4L * 1024L * 1024L;

  private static final int T_UINT8 = 0;
  private static final int T_INT8 = 1;
  private static final int T_UINT16 = 2;
  private static final int T_INT16 = 3;
  private static final int T_UINT32 = 4;
  private static final int T_INT32 = 5;
  private static final int T_FLOAT32 = 6;
  private static final int T_BOOL = 7;
  private static final int T_STRING = 8;
  private static final int T_ARRAY = 9;
  private static final int T_UINT64 = 10;
  private static final int T_INT64 = 11;
  private static final int T_FLOAT64 = 12;

  private record CacheKey(String path, long mtime) {}

  private final ConcurrentMap<CacheKey, ModelDetails> cache = new ConcurrentHashMap<>();

  /**
   * Reads GGUF metadata for the given file. Cached by (path, mtime).
   *
   * @param path absolute path of the .gguf file
   * @return parsed details
   * @throws IOException if the file can't be read or isn't a valid GGUF
   */
  public ModelDetails read(Path path) throws IOException {
    long mtime = java.nio.file.Files.getLastModifiedTime(path).toMillis();
    CacheKey key = new CacheKey(path.toString(), mtime);
    ModelDetails cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    ModelDetails details = parse(path);
    cache.put(key, details);
    return details;
  }

  private ModelDetails parse(Path path) throws IOException {
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      long size = Math.min(ch.size(), MAP_BYTES);
      MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
      buf.order(ByteOrder.LITTLE_ENDIAN);

      byte[] magic = new byte[4];
      buf.get(magic);
      if (!Arrays.equals(magic, MAGIC)) {
        throw new IOException("Not a GGUF file: " + path);
      }
      int version = buf.getInt();
      if (version < 2) {
        throw new IOException("Unsupported GGUF version " + version + " for " + path);
      }
      buf.getLong(); // tensor_count — unused here
      long kvCount = buf.getLong();

      String arch = null;
      Integer ctxLen = null;
      Integer blockCount = null;
      Integer headCount = null;
      Integer expertCount = null;
      Integer expertUsedCount = null;

      for (long i = 0; i < kvCount; i++) {
        if (buf.remaining() < 12) {
          break;
        }
        String key = readString(buf);
        int type = buf.getInt();

        Number num = null;
        String str = null;

        if (type == T_STRING) {
          str = readString(buf);
        } else if (isPrimitiveNumber(type)) {
          num = readNumber(buf, type);
        } else {
          skipValue(buf, type);
        }

        if (str != null && "general.architecture".equals(key)) {
          arch = str;
        } else if (num != null) {
          if (key.endsWith(".context_length")) {
            ctxLen = num.intValue();
          } else if (key.endsWith(".block_count")) {
            blockCount = num.intValue();
          } else if (key.endsWith(".attention.head_count")) {
            headCount = num.intValue();
          } else if (key.endsWith(".expert_count")) {
            expertCount = num.intValue();
          } else if (key.endsWith(".expert_used_count")) {
            expertUsedCount = num.intValue();
          }
        }
      }

      LOG.debug(
          "Parsed GGUF {}: arch={} ctx={} blocks={} heads={} experts={}/{}",
          path.getFileName(),
          arch,
          ctxLen,
          blockCount,
          headCount,
          expertUsedCount,
          expertCount);
      return new ModelDetails(
          path.toString(), arch, ctxLen, blockCount, headCount, expertCount, expertUsedCount);
    }
  }

  private static String readString(ByteBuffer buf) throws IOException {
    long len = buf.getLong();
    if (len < 0 || len > MAX_STRING_BYTES) {
      throw new IOException("Invalid GGUF string length: " + len);
    }
    byte[] bytes = new byte[(int) len];
    buf.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static boolean isPrimitiveNumber(int type) {
    return switch (type) {
      case T_UINT8, T_INT8, T_UINT16, T_INT16, T_UINT32,
          T_INT32, T_FLOAT32, T_UINT64, T_INT64, T_FLOAT64 -> true;
      default -> false;
    };
  }

  private static Number readNumber(ByteBuffer buf, int type) {
    return switch (type) {
      case T_UINT8 -> buf.get() & 0xFF;
      case T_INT8 -> buf.get();
      case T_UINT16 -> buf.getShort() & 0xFFFF;
      case T_INT16 -> buf.getShort();
      case T_UINT32 -> buf.getInt() & 0xFFFFFFFFL;
      case T_INT32 -> buf.getInt();
      case T_FLOAT32 -> buf.getFloat();
      case T_UINT64, T_INT64 -> buf.getLong();
      case T_FLOAT64 -> buf.getDouble();
      default -> throw new IllegalStateException("Not a number type: " + type);
    };
  }

  private static void skipValue(ByteBuffer buf, int type) throws IOException {
    switch (type) {
      case T_BOOL -> buf.position(buf.position() + 1);
      case T_STRING -> readString(buf);
      case T_ARRAY -> skipArray(buf);
      default -> {
        if (isPrimitiveNumber(type)) {
          readNumber(buf, type);
        } else {
          throw new IOException("Unknown GGUF type: " + type);
        }
      }
    }
  }

  private static void skipArray(ByteBuffer buf) throws IOException {
    int subtype = buf.getInt();
    long len = buf.getLong();
    int sz = primitiveSize(subtype);
    if (sz > 0) {
      long total = len * sz;
      if (total > buf.remaining()) {
        throw new IOException("Array overruns mapped region; metadata too large to parse");
      }
      buf.position(buf.position() + (int) total);
      return;
    }
    if (subtype == T_BOOL) {
      buf.position(buf.position() + (int) len);
      return;
    }
    if (subtype == T_STRING) {
      for (long i = 0; i < len; i++) {
        readString(buf);
      }
      return;
    }
    if (subtype == T_ARRAY) {
      for (long i = 0; i < len; i++) {
        skipArray(buf);
      }
      return;
    }
    throw new IOException("Unknown GGUF array subtype: " + subtype);
  }

  private static int primitiveSize(int type) {
    return switch (type) {
      case T_UINT8, T_INT8 -> 1;
      case T_UINT16, T_INT16 -> 2;
      case T_UINT32, T_INT32, T_FLOAT32 -> 4;
      case T_UINT64, T_INT64, T_FLOAT64 -> 8;
      default -> -1;
    };
  }
}
