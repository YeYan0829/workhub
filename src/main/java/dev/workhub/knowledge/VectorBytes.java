package dev.workhub.knowledge;

import dev.workhub.messaging.WorkFailure;
import java.nio.*;
import java.security.*;
import java.util.*;

public final class VectorBytes {
  private VectorBytes() {}

  public static byte[] encode(float[] values, int dimension) {
    if (values.length != dimension) throw new WorkFailure(false, "EMBEDDING_DIMENSION_MISMATCH");
    double square = 0;
    for (float v : values) {
      if (!Float.isFinite(v)) throw new WorkFailure(false, "EMBEDDING_NONFINITE");
      square += (double) v * v;
    }
    if (square == 0 || !Double.isFinite(square))
      throw new WorkFailure(false, "EMBEDDING_ZERO_VECTOR");
    var buffer = ByteBuffer.allocate(dimension * 4).order(ByteOrder.LITTLE_ENDIAN);
    double norm = Math.sqrt(square);
    for (float v : values) buffer.putFloat((float) (v / norm));
    return buffer.array();
  }

  public static List<Float> decode(byte[] data, int dimension, String hash) {
    if (data.length != dimension * 4 || !hash(data).equals(hash))
      throw new WorkFailure(false, "ARTIFACT_HASH_MISMATCH");
    var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    List<Float> values = new ArrayList<>();
    double square = 0;
    for (int i = 0; i < dimension; i++) {
      float v = buffer.getFloat();
      if (!Float.isFinite(v)) throw new WorkFailure(false, "ARTIFACT_NONFINITE");
      values.add(v);
      square += (double) v * v;
    }
    if (Math.abs(square - 1) > 1e-4)
      throw new WorkFailure(false, "ARTIFACT_NORMALIZATION_MISMATCH");
    return List.copyOf(values);
  }

  public static String hash(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
