package com.twixxel.stalker.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluador minimo de Molang para lo que usa el addon original:
 * comparaciones, && || !, ?:, aritmetica, math.*, query.property('twixxel:x'), v.x, true/false.
 */
public final class Molang {
    public interface Context {
        double prop(String name);

        double var(String name);

        boolean allAnimationsFinished();

        double animTime();

        double lifeTime();
    }

    @FunctionalInterface
    public interface Expr {
        double eval(Context c);
    }

    private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();
    private static final Expr ZERO = c -> 0;

    private Molang() {
    }

    public static boolean truthy(double v) {
        return v != 0.0;
    }

    public static Expr compile(String src) {
        if (src == null) return ZERO;
        return CACHE.computeIfAbsent(src, s -> {
            try {
                return new Parser(s).parseAll();
            } catch (RuntimeException e) {
                System.err.println("[twixxel] Molang no valido: '" + s + "': " + e.getMessage());
                return ZERO;
            }
        });
    }

    public static double eval(String src, Context c) {
        return compile(src).eval(c);
    }

    // ------------------------------------------------------------------------------------------
    private static final class Parser {
        private final List<String> toks = new ArrayList<>();
        private int p = 0;

        Parser(String s) {
            tokenize(s);
        }

        private void tokenize(String s) {
            int i = 0, n = s.length();
            while (i < n) {
                char ch = s.charAt(i);
                if (Character.isWhitespace(ch)) {
                    i++;
                } else if (Character.isDigit(ch) || (ch == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                    int j = i;
                    while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                    toks.add(s.substring(i, j));
                    i = j;
                } else if (Character.isLetter(ch) || ch == '_') {
                    int j = i;
                    while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.')) j++;
                    toks.add(s.substring(i, j));
                    i = j;
                } else if (ch == '\'') {
                    int j = s.indexOf('\'', i + 1);
                    if (j < 0) throw new IllegalArgumentException("string sin cerrar");
                    toks.add(s.substring(i, j + 1));
                    i = j + 1;
                } else {
                    if (i + 1 < n) {
                        String two = s.substring(i, i + 2);
                        if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                                || two.equals("<=") || two.equals(">=")) {
                            toks.add(two);
                            i += 2;
                            continue;
                        }
                    }
                    toks.add(String.valueOf(ch));
                    i++;
                }
            }
        }

        private String peek() {
            return p < toks.size() ? toks.get(p) : "";
        }

        private String next() {
            return p < toks.size() ? toks.get(p++) : "";
        }

        private boolean accept(String t) {
            if (peek().equals(t)) {
                p++;
                return true;
            }
            return false;
        }

        Expr parseAll() {
            Expr e = ternary();
            if (p < toks.size()) throw new IllegalArgumentException("sobra '" + peek() + "'");
            return e;
        }

        private Expr ternary() {
            Expr cond = or();
            if (accept("?")) {
                Expr a = ternary();
                if (!accept(":")) throw new IllegalArgumentException("falta ':'");
                Expr b = ternary();
                return c -> truthy(cond.eval(c)) ? a.eval(c) : b.eval(c);
            }
            return cond;
        }

        private Expr or() {
            Expr l = and();
            while (accept("||")) {
                Expr a = l, b = and();
                l = c -> (truthy(a.eval(c)) || truthy(b.eval(c))) ? 1 : 0;
            }
            return l;
        }

        private Expr and() {
            Expr l = eq();
            while (accept("&&")) {
                Expr a = l, b = eq();
                l = c -> (truthy(a.eval(c)) && truthy(b.eval(c))) ? 1 : 0;
            }
            return l;
        }

        private Expr eq() {
            Expr l = rel();
            while (true) {
                if (accept("==")) {
                    Expr a = l, b = rel();
                    l = c -> Math.abs(a.eval(c) - b.eval(c)) < 1e-9 ? 1 : 0;
                } else if (accept("!=")) {
                    Expr a = l, b = rel();
                    l = c -> Math.abs(a.eval(c) - b.eval(c)) < 1e-9 ? 0 : 1;
                } else return l;
            }
        }

        private Expr rel() {
            Expr l = add();
            while (true) {
                if (accept("<=")) {
                    Expr a = l, b = add();
                    l = c -> a.eval(c) <= b.eval(c) ? 1 : 0;
                } else if (accept(">=")) {
                    Expr a = l, b = add();
                    l = c -> a.eval(c) >= b.eval(c) ? 1 : 0;
                } else if (accept("<")) {
                    Expr a = l, b = add();
                    l = c -> a.eval(c) < b.eval(c) ? 1 : 0;
                } else if (accept(">")) {
                    Expr a = l, b = add();
                    l = c -> a.eval(c) > b.eval(c) ? 1 : 0;
                } else return l;
            }
        }

        private Expr add() {
            Expr l = mul();
            while (true) {
                if (accept("+")) {
                    Expr a = l, b = mul();
                    l = c -> a.eval(c) + b.eval(c);
                } else if (accept("-")) {
                    Expr a = l, b = mul();
                    l = c -> a.eval(c) - b.eval(c);
                } else return l;
            }
        }

        private Expr mul() {
            Expr l = unary();
            while (true) {
                if (accept("*")) {
                    Expr a = l, b = unary();
                    l = c -> a.eval(c) * b.eval(c);
                } else if (accept("/")) {
                    Expr a = l, b = unary();
                    l = c -> {
                        double d = b.eval(c);
                        return d == 0 ? 0 : a.eval(c) / d;
                    };
                } else return l;
            }
        }

        private Expr unary() {
            if (accept("-")) {
                Expr e = unary();
                return c -> -e.eval(c);
            }
            if (accept("+")) return unary();
            if (accept("!")) {
                Expr e = unary();
                return c -> truthy(e.eval(c)) ? 0 : 1;
            }
            return primary();
        }

        private Expr primary() {
            String t = next();
            if (t.isEmpty()) throw new IllegalArgumentException("expresion incompleta");
            char c0 = t.charAt(0);
            if (Character.isDigit(c0) || c0 == '.') {
                double v = Double.parseDouble(t);
                return c -> v;
            }
            if (t.equals("(")) {
                Expr e = ternary();
                if (!accept(")")) throw new IllegalArgumentException("falta ')'");
                return e;
            }
            if (c0 == '\'') {
                throw new IllegalArgumentException("string inesperado");
            }
            String name = t.toLowerCase(Locale.ROOT);
            if (name.equals("true")) return c -> 1;
            if (name.equals("false")) return c -> 0;

            // llamada a funcion
            if (peek().equals("(")) {
                next();
                List<Object> args = new ArrayList<>();
                if (!accept(")")) {
                    do {
                        if (peek().startsWith("'")) args.add(next());
                        else args.add(ternary());
                    } while (accept(","));
                    if (!accept(")")) throw new IllegalArgumentException("falta ')' en llamada");
                }
                return call(name, args);
            }

            if (name.startsWith("q.")) name = "query." + name.substring(2);
            if (name.startsWith("v.")) name = "variable." + name.substring(2);
            if (name.equals("query.all_animations_finished")) return c -> c.allAnimationsFinished() ? 1 : 0;
            if (name.equals("query.anim_time")) return Context::animTime;
            if (name.equals("query.life_time")) return Context::lifeTime;
            if (name.startsWith("variable.")) {
                String v = name.substring("variable.".length());
                return c -> c.var(v);
            }
            if (name.equals("math.pi")) return c -> Math.PI;
            return c -> 0; // query desconocida -> 0
        }

        @SuppressWarnings("unchecked")
        private Expr call(String name, List<Object> args) {
            if (name.equals("query.property") || name.equals("q.property")) {
                String key = (String) args.get(0);
                key = key.substring(1, key.length() - 1);
                int colon = key.indexOf(':');
                String k = colon >= 0 ? key.substring(colon + 1) : key;
                return c -> c.prop(k);
            }
            Expr a = args.size() > 0 && args.get(0) instanceof Expr ? (Expr) args.get(0) : ZERO;
            Expr b = args.size() > 1 && args.get(1) instanceof Expr ? (Expr) args.get(1) : ZERO;
            Expr d = args.size() > 2 && args.get(2) instanceof Expr ? (Expr) args.get(2) : ZERO;
            switch (name) {
                case "math.sin":
                    return c -> Math.sin(Math.toRadians(a.eval(c)));
                case "math.cos":
                    return c -> Math.cos(Math.toRadians(a.eval(c)));
                case "math.acos":
                    return c -> Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.eval(c)))));
                case "math.asin":
                    return c -> Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, a.eval(c)))));
                case "math.abs":
                    return c -> Math.abs(a.eval(c));
                case "math.sqrt":
                    return c -> Math.sqrt(Math.max(0, a.eval(c)));
                case "math.floor":
                    return c -> Math.floor(a.eval(c));
                case "math.ceil":
                    return c -> Math.ceil(a.eval(c));
                case "math.round":
                    return c -> Math.round(a.eval(c));
                case "math.max":
                    return c -> Math.max(a.eval(c), b.eval(c));
                case "math.min":
                    return c -> Math.min(a.eval(c), b.eval(c));
                case "math.pow":
                    return c -> Math.pow(a.eval(c), b.eval(c));
                case "math.clamp":
                    return c -> Math.max(b.eval(c), Math.min(d.eval(c), a.eval(c)));
                case "math.lerp":
                    return c -> a.eval(c) + (b.eval(c) - a.eval(c)) * d.eval(c);
                default:
                    return ZERO;
            }
        }
    }
}
