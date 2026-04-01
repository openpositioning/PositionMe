package com.openpositioning.PositionMe.utils;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class PathRouter {

    private static final float EPS = 0.05f;

    private static final class Graph {
        final float[][] nodes;
        final float[][] adj;
        final List<float[]> solid;
        final List<float[]> trans;
        Graph(float[][] nodes, float[][] adj, List<float[]> solid, List<float[]> trans) {
            this.nodes = nodes; this.adj = adj; this.solid = solid; this.trans = trans;
        }
        int n() { return nodes.length; }
    }

    private final AtomicReference<Graph> graphRef = new AtomicReference<>();

    public void rebuild(List<float[]> solid, List<float[]> transitions) {
        List<float[]> s = solid       != null ? new ArrayList<>(solid)       : Collections.emptyList();
        List<float[]> t = transitions != null ? new ArrayList<>(transitions) : Collections.emptyList();
        new Thread(() -> graphRef.set(buildGraph(s, t))).start();
    }

    public List<float[]> route(float[] start, float[] end, boolean floorChanged) {
        Graph g = graphRef.get();
        if (g == null) return Collections.singletonList(end);
        List<float[]> active = floorChanged ? g.solid : concat(g.solid, g.trans);
        if (!crossed(start[0], start[1], end[0], end[1], active)) {
            return Collections.singletonList(end);
        }
        return astar(g, start, end, active, !floorChanged && !g.trans.isEmpty());
    }

    private static Graph buildGraph(List<float[]> solid, List<float[]> trans) {
        List<float[]> verts = dedup(concat(solid, trans));
        int n = verts.size();
        float[][] nodes = verts.toArray(new float[0][]);
        float[][] adj = new float[n][n];
        for (float[] r : adj) Arrays.fill(r, -1f);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!crossed(nodes[i][0], nodes[i][1], nodes[j][0], nodes[j][1], solid)) {
                    float d = dst(nodes[i], nodes[j]);
                    adj[i][j] = adj[j][i] = d;
                }
            }
        }
        return new Graph(nodes, adj, solid, trans);
    }

    private static List<float[]> astar(Graph g, float[] start, float[] end,
                                        List<float[]> active, boolean checkTrans) {
        int n = g.n(), S = n, E = n + 1, T = n + 2;
        float[] gs = fill(T), fs = fill(T);
        int[] prev = new int[T];
        boolean[] closed = new boolean[T];
        Arrays.fill(prev, -1);
        gs[S] = 0f;
        fs[S] = dst(start, end);
        PriorityQueue<Integer> open = new PriorityQueue<>(Comparator.comparingDouble(i -> fs[i]));
        open.add(S);
        while (!open.isEmpty()) {
            int cur = open.poll();
            if (closed[cur]) continue;
            closed[cur] = true;
            if (cur == E) return buildPath(prev, S, E, n, g, start, end);
            for (int nb = 0; nb < T; nb++) {
                if (closed[nb]) continue;
                float w = ew(g, cur, nb, S, E, start, end, active, checkTrans);
                if (w < 0) continue;
                float ng = gs[cur] + w;
                if (ng < gs[nb]) {
                    gs[nb] = ng;
                    fs[nb] = ng + dst(pt(g, nb, S, E, start, end), end);
                    prev[nb] = cur;
                    open.add(nb);
                }
            }
        }
        return Collections.singletonList(end);
    }

    private static float ew(Graph g, int a, int b, int S, int E,
                             float[] start, float[] end, List<float[]> active, boolean checkTrans) {
        float[] pa = pt(g, a, S, E, start, end);
        float[] pb = pt(g, b, S, E, start, end);
        if (a < g.n() && b < g.n()) {
            if (g.adj[a][b] < 0) return -1f;
            if (checkTrans && crossed(pa[0], pa[1], pb[0], pb[1], g.trans)) return -1f;
            return g.adj[a][b];
        }
        return crossed(pa[0], pa[1], pb[0], pb[1], active) ? -1f : dst(pa, pb);
    }

    private static float[] pt(Graph g, int i, int S, int E, float[] s, float[] e) {
        if (i == S) return s;
        if (i == E) return e;
        return g.nodes[i];
    }

    private static List<float[]> buildPath(int[] prev, int S, int E, int n,
                                            Graph g, float[] s, float[] e) {
        List<float[]> path = new ArrayList<>();
        for (int cur = E; cur != S && cur != -1; cur = prev[cur]) {
            path.add(0, pt(g, cur, S, E, s, e));
        }
        return path.isEmpty() ? Collections.singletonList(e) : path;
    }

    private static boolean crossed(float ax, float ay, float bx, float by, List<float[]> walls) {
        for (float[] w : walls) {
            if (coin(ax, ay, w[0], w[1]) || coin(ax, ay, w[2], w[3]) ||
                coin(bx, by, w[0], w[1]) || coin(bx, by, w[2], w[3])) continue;
            if (seg(ax, ay, bx, by, w[0], w[1], w[2], w[3])) return true;
        }
        return false;
    }

    private static boolean coin(float ax, float ay, float bx, float by) {
        return Math.abs(ax - bx) < EPS && Math.abs(ay - by) < EPS;
    }

    private static boolean seg(float ax, float ay, float bx, float by,
                                float cx, float cy, float dx, float dy) {
        float d1 = ccw(cx, cy, dx, dy, ax, ay), d2 = ccw(cx, cy, dx, dy, bx, by);
        float d3 = ccw(ax, ay, bx, by, cx, cy), d4 = ccw(ax, ay, bx, by, dx, dy);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
            && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static float ccw(float ox, float oy, float ax, float ay, float bx, float by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    private static float dst(float[] a, float[] b) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static List<float[]> dedup(List<float[]> segs) {
        List<float[]> list = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (float[] seg : segs) {
            add(seg[0], seg[1], list, seen);
            add(seg[2], seg[3], list, seen);
        }
        return list;
    }

    private static void add(float x, float y, List<float[]> list, Set<Long> seen) {
        long key = (long) Math.round(x / 0.05f) * 4_000_000L + (long) Math.round(y / 0.05f);
        if (seen.add(key)) list.add(new float[]{x, y});
    }

    private static List<float[]> concat(List<float[]> a, List<float[]> b) {
        List<float[]> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    private static float[] fill(int n) {
        float[] a = new float[n];
        Arrays.fill(a, Float.MAX_VALUE);
        return a;
    }
}
