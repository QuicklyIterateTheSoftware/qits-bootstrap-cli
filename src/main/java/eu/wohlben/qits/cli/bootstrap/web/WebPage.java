package eu.wohlben.qits.cli.bootstrap.web;

/**
 * The whole browser view: one page, no assets.
 * <p>
 * It is a Java constant rather than a file under {@code resources} on purpose. This CLI is a native
 * binary and nothing else, and a string in a class is in the image by construction — nothing to
 * register, nothing to include, nothing that can be right on the classpath under the tests and
 * missing in the binary people run.
 */
public final class WebPage {

    private WebPage() {
    }

    public static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>qits bootstrap</title>
            <style>
              :root {
                --bg: #0b0e14; --fg: #c8ccd4; --dim: #6b7280; --line: #1c2230;
                --cyan: #57c7ff; --green: #5ad48a; --yellow: #e8c26a; --red: #ff6b6b;
              }
              * { box-sizing: border-box; }
              html, body { height: 100%; margin: 0; }
              body {
                background: var(--bg); color: var(--fg); display: flex; flex-direction: column;
                font: 13px/1.45 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
              }
              header { padding: 10px 14px 8px; border-bottom: 1px solid var(--line); }
              h1 { margin: 0 0 6px; font-size: 13px; font-weight: 600; color: var(--cyan); }
              h1 .log { color: var(--dim); font-weight: 400; }
              #phases { display: flex; flex-direction: column; gap: 1px; }
              .row { white-space: pre; overflow: hidden; text-overflow: ellipsis; }
              /* The tick is drawn, not typed: U+2713 is missing from enough monospace fonts
                 that a done phase would be marked with a box on the machines that lack it. */
              .tick {
                width: 1ch; height: 1em; vertical-align: -.15em; fill: none;
                stroke: currentColor; stroke-width: 1.6; stroke-linecap: round;
                stroke-linejoin: round;
              }
              .done { color: var(--green); opacity: .65; }
              .skipped, .pending, .earlier { color: var(--dim); }
              .warned { color: var(--yellow); }
              .failed { color: var(--red); font-weight: 600; }
              .running { color: var(--fg); font-weight: 600; }
              .status { color: var(--yellow); padding-left: 2ch; white-space: pre-wrap; }
              .summary { margin-top: 4px; font-weight: 600; }
              .ok { color: var(--green); }
              .bad { color: var(--red); }
              .note { color: var(--dim); font-weight: 400; }
              /* The lower half is two columns: the running step on the left, what the platform
                 announced on the right. Stacked below 720px, where side by side leaves neither
                 column wide enough to read. */
              #lower { flex: 1; display: flex; min-height: 0; }
              #tail, #events {
                margin: 0; padding: 8px 14px; overflow: auto; white-space: pre-wrap;
                word-break: break-word; font: inherit; color: var(--fg);
              }
              #tail { flex: 1; min-width: 0; }
              #events {
                flex: 0 0 clamp(240px, 30%, 460px); border-left: 1px solid var(--line);
                color: var(--dim);
              }
              #events::before {
                content: 'platform events'; display: block; color: var(--cyan);
                padding-bottom: 4px;
              }
              @media (max-width: 720px) {
                #lower { flex-direction: column; }
                #events {
                  flex: 0 0 30%; border-left: none; border-top: 1px solid var(--line);
                }
              }
              #foot {
                border-top: 1px solid var(--line); padding: 4px 14px; color: var(--dim);
                display: flex; gap: 14px;
              }
              #foot .off { color: var(--yellow); }
              a, a:visited { color: var(--cyan); }
              button {
                background: none; border: 1px solid var(--line); color: var(--dim);
                font: inherit; padding: 0 6px; cursor: pointer;
              }
            </style>
            </head>
            <body>
            <header>
              <h1>qits bootstrap &middot; <span id="elapsed">0s</span> elapsed
                <span class="log">&middot; log <span id="log"></span></span></h1>
              <div id="phases"></div>
              <div id="summary" class="summary"></div>
            </header>
            <div id="lower">
              <pre id="tail"></pre>
              <pre id="events"></pre>
            </div>
            <div id="foot">
              <span id="conn">connecting…</span>
              <span id="counts"></span>
              <button id="all">show every phase</button>
            </div>
            <script>
            (function () {
              var MAX_TAIL = 2000;
              var MAX_EVENTS = 500;
              var DONE_ROWS = 8;
              var state = { phases: [], currentIndex: -1, status: '', summary: '',
                            exitCode: null, log: '', total: 0 };
              var base = { run: 0, current: 0, at: Date.now() };
              var showAll = false;
              var tailEl = document.getElementById('tail');
              var eventsEl = document.getElementById('events');
              var phasesEl = document.getElementById('phases');

              function fmt(ms) {
                var s = Math.max(0, Math.floor(ms / 1000));
                if (s < 60) { return s + 's'; }
                var m = Math.floor(s / 60);
                if (s < 3600) { return m + 'm' + String(s % 60).padStart(2, '0') + 's'; }
                return Math.floor(s / 3600) + 'h' + String(m % 60).padStart(2, '0') + 'm';
              }
              var TICK = '<svg class="tick" viewBox="0 0 12 12" aria-hidden="true">'
                + '<polyline points="2,6.4 4.6,9.1 10,3.2"></polyline></svg>';
              function mark(st) {
                if (st === 'DONE') { return TICK; }
                if (st === 'SKIPPED') { return '&middot;'; }
                if (st === 'WARNED') { return '!'; }
                if (st === 'FAILED') { return '&times;'; }
                return ' ';
              }
              function esc(text) {
                return String(text === null || text === undefined ? '' : text)
                  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
              }
              function row(cls, html) {
                return '<div class="row ' + cls + '">' + html + '</div>';
              }
              function note(p) {
                return p.note ? ' <span class="note">&mdash; ' + esc(p.note) + '</span>' : '';
              }
              function elapsedOf(i) {
                return i === state.currentIndex
                  ? base.current + (Date.now() - base.at)
                  : (state.phases[i] || {}).tookMs || 0;
              }

              function render() {
                var total = state.phases.length || state.total;
                var html = '';
                var settled = [];
                for (var i = 0; i < state.phases.length; i++) {
                  var st = state.phases[i].state;
                  if (st !== 'PENDING' && st !== 'RUNNING') { settled.push(i); }
                }
                var from = showAll ? 0 : Math.max(0, settled.length - DONE_ROWS);
                if (from > 0) {
                  html += row('earlier', '&nbsp;&nbsp;… ' + from + ' earlier phase'
                    + (from === 1 ? '' : 's') + ' done');
                }
                for (var k = from; k < settled.length; k++) {
                  var j = settled[k];
                  var p = state.phases[j];
                  html += row(p.state.toLowerCase(), '&nbsp;' + mark(p.state) + ' ' + (j + 1)
                    + '/' + total + ' ' + esc(p.title) + ' (' + fmt(p.tookMs) + ')' + note(p));
                }
                if (state.currentIndex >= 0 && state.phases[state.currentIndex]) {
                  var c = state.phases[state.currentIndex];
                  html += row('running', '&rsaquo; ' + (state.currentIndex + 1) + '/' + total + ' '
                    + esc(c.title) + '   ' + fmt(elapsedOf(state.currentIndex)));
                  if (state.status) {
                    html += '<div class="row status">' + esc(state.status) + '</div>';
                  }
                }
                var pending = total - settled.length - (state.currentIndex >= 0 ? 1 : 0);
                if (pending > 0) {
                  var next = state.phases[settled.length + (state.currentIndex >= 0 ? 1 : 0)];
                  html += row('pending', '&nbsp;&nbsp;' + pending + ' phase'
                    + (pending === 1 ? '' : 's') + ' pending'
                    + (next ? ' &mdash; next: ' + esc(next.title) : ''));
                }
                phasesEl.innerHTML = html;
                document.getElementById('elapsed').textContent =
                  fmt(base.run + (Date.now() - base.at));
                document.getElementById('log').textContent = state.log;
                var sum = document.getElementById('summary');
                sum.textContent = state.summary;
                sum.className = 'summary ' + (state.exitCode === 0 ? 'ok'
                  : (state.exitCode === null ? '' : 'bad'));
                document.getElementById('counts').textContent =
                  settled.length + '/' + total + ' phases';
              }

              /* Both columns behave the same way, so they share one pair of functions: a reader
                 who scrolled up stays where they are, and one at the bottom is carried along. */
              function atBottom(el) {
                return el.scrollHeight - el.scrollTop - el.clientHeight < 40;
              }
              function appendTo(el, lines, max) {
                var stick = atBottom(el);
                var text = el.textContent;
                text += (text ? '\\n' : '') + lines.join('\\n');
                var all = text.split('\\n');
                if (all.length > max) { all = all.slice(all.length - max); }
                el.textContent = all.join('\\n');
                if (stick) { el.scrollTop = el.scrollHeight; }
              }
              function setLines(el, lines) {
                el.textContent = lines.join('\\n');
                el.scrollTop = el.scrollHeight;
              }

              function conn(text, off) {
                var el = document.getElementById('conn');
                el.textContent = text;
                el.className = off ? 'off' : '';
              }

              var es = new EventSource('events');
              es.addEventListener('snapshot', function (e) {
                var s = JSON.parse(e.data);
                state = s;
                base = { run: s.runElapsedMs, current: s.currentElapsedMs, at: Date.now() };
                setLines(tailEl, s.tail || []);
                setLines(eventsEl, s.events || []);
                render();
              });
              es.addEventListener('phase', function (e) {
                var p = JSON.parse(e.data);
                state.phases[p.index] = p;
                if (p.index === state.currentIndex && p.state !== 'RUNNING') {
                  state.currentIndex = -1;
                  state.status = '';
                }
                render();
              });
              es.addEventListener('status', function (e) {
                state.status = JSON.parse(e.data).text;
                render();
              });
              es.addEventListener('line', function (e) {
                appendTo(tailEl, [JSON.parse(e.data).text], MAX_TAIL);
              });
              es.addEventListener('ev', function (e) {
                appendTo(eventsEl, [JSON.parse(e.data).text], MAX_EVENTS);
              });
              es.addEventListener('done', function (e) {
                var d = JSON.parse(e.data);
                state.summary = d.summary;
                state.exitCode = d.exitCode;
                state.currentIndex = -1;
                render();
              });
              es.onopen = function () { conn('live', false); };
              es.onerror = function () { conn('not connected — the run may have ended', true); };

              document.getElementById('all').addEventListener('click', function () {
                showAll = !showAll;
                this.textContent = showAll ? 'show recent phases' : 'show every phase';
                render();
              });
              setInterval(render, 250);
              render();
            })();
            </script>
            </body>
            </html>
            """;
}
