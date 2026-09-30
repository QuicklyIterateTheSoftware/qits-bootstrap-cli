package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.api.Http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * An http that answers by method and url and keeps every call it was asked.
 * <p>
 * What a phase test asserts with it is what went over the wire, because that is what a live boot
 * would have done. A call nothing answers is a 500 naming itself, so a test that forgot one fails
 * on the call rather than on whatever the phase made of an empty answer.
 */
final class CannedHttp extends Http {

    final List<String> calls = new ArrayList<>();
    final Map<String, String> bodies = new HashMap<>();
    final Map<String, Map<String, String>> headers = new HashMap<>();
    private final Map<String, Supplier<Http.Response>> answers = new HashMap<>();

    CannedHttp answer(String call, int status, String body) {
        answers.put(call, () -> new Http.Response(status, body));
        return this;
    }

    /** An answer that is decided when it is asked for — a listing that changes between two reads. */
    CannedHttp answer(String call, Supplier<Http.Response> answer) {
        answers.put(call, answer);
        return this;
    }

    private Http.Response answer(String call, String body, Map<String, String> sent) {
        calls.add(call);
        bodies.put(call, body);
        headers.put(call, sent);
        Supplier<Http.Response> answer = answers.get(call);
        return answer == null ? new Http.Response(500, "unexpected " + call) : answer.get();
    }

    @Override
    public Http.Response get(String url, Map<String, String> sent) {
        return answer("GET " + url, null, sent);
    }

    @Override
    public Http.Response head(String url, Map<String, String> sent) {
        return answer("HEAD " + url, null, sent);
    }

    @Override
    public Http.Response postJson(String url, String json, Map<String, String> sent) {
        return answer("POST " + url, json, sent);
    }

    @Override
    public Http.Response putJson(String url, String json, Map<String, String> sent) {
        return answer("PUT " + url, json, sent);
    }

    @Override
    public Http.Response delete(String url, Map<String, String> sent) {
        return answer("DELETE " + url, null, sent);
    }
}
