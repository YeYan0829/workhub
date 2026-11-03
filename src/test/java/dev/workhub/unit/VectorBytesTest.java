package dev.workhub.unit;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.knowledge.VectorBytes;
import dev.workhub.messaging.WorkFailure;
import java.nio.*;
import org.junit.jupiter.api.Test;

class VectorBytesTest {
  @Test
  void canonicalNormalizedEncodingAndIntegrity() {
    byte[] bytes = VectorBytes.encode(new float[] {3, 4, 0}, 3);
    assertThat(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat()).isEqualTo(.6f);
    assertThat(VectorBytes.decode(bytes, 3, VectorBytes.hash(bytes))).containsExactly(.6f, .8f, 0f);
    byte[] corrupt = bytes.clone();
    corrupt[0] ^= 1;
    assertThatThrownBy(() -> VectorBytes.decode(corrupt, 3, VectorBytes.hash(bytes)))
        .isInstanceOf(WorkFailure.class)
        .hasMessage("ARTIFACT_HASH_MISMATCH");
  }

  @Test
  void invalidVectorsNeverBecomeArtifacts() {
    for (float[] value : new float[][] {{0, 0}, {Float.NaN, 1}, {Float.POSITIVE_INFINITY, 1}, {1}})
      assertThatThrownBy(() -> VectorBytes.encode(value, 2)).isInstanceOf(WorkFailure.class);
  }
}
