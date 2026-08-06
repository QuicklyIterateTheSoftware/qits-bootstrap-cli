package eu.wohlben.qits.cli.bootstrap.proc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TailBufferTest {

    @Test
    void keepsOnlyTheLastLines() {
        TailBuffer tail = new TailBuffer(3);
        for (int i = 1; i <= 10; i++) {
            tail.add("line" + i);
        }

        assertThat(tail.all()).containsExactly("line8", "line9", "line10");
        assertThat(tail.size()).isEqualTo(3);
        assertThat(tail.dropped()).isEqualTo(7);
    }

    @Test
    void lastAsksForNoMoreThanItHas() {
        TailBuffer tail = new TailBuffer(100);
        tail.add("only");

        assertThat(tail.last(20)).containsExactly("only");
        assertThat(tail.last(1)).containsExactly("only");
    }

    @Test
    void takesTheEndWhenAskedForFewerThanItHolds() {
        TailBuffer tail = new TailBuffer(100);
        for (int i = 0; i < 10; i++) {
            tail.add("l" + i);
        }

        assertThat(tail.last(3)).containsExactly("l7", "l8", "l9");
    }

    @Test
    void refusesAnEmptyTail() {
        assertThatThrownBy(() -> new TailBuffer(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
