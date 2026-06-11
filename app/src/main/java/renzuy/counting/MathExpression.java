package renzuy.counting;

/**
 * Tiny integer-arithmetic evaluator for the counting game.
 *
 * <p>Accepts {@code + - * / % ^} with parentheses and unary minus over 64-bit
 * integers, so {@code 47+30} validates as 77. Everything is exact: overflow,
 * division with a remainder, division by zero, or a negative/huge exponent all
 * make the expression invalid (returns {@code null}) — there is no floating
 * point to smuggle rounding through, and no {@code eval} of any kind.
 *
 * <p>Grammar (recursive descent, {@code ^} is right-associative):
 * <pre>
 *   expr   := term (('+' | '-') term)*
 *   term   := factor (('*' | 'x' | '×' | '/' | '÷' | '%') factor)*
 *   factor := unary ('^' factor)?
 *   unary  := '-' unary | number | '(' expr ')'
 * </pre>
 */
public final class MathExpression {

    /** Hard cap so nobody feeds the parser a novel. */
    public static final int MAX_LENGTH = 100;

    private static final int MAX_EXPONENT = 63;

    private final String src;
    private int pos;

    private MathExpression(String src) {
        this.src = src;
    }

    /**
     * A message is a counting attempt iff it is made only of expression
     * characters and contains at least one digit. Anything else (chat,
     * emoji, links) is ignored by the game rather than punished.
     */
    public static boolean looksLikeAttempt(String content) {
        if (content == null) return false;
        String s = content.strip();
        if (s.isEmpty() || s.length() > MAX_LENGTH) return false;
        boolean digit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if ("+-*/%^()x×÷ \t".indexOf(c) < 0) {
                return false;
            }
        }
        return digit;
    }

    /** @return the exact integer value, or {@code null} if the expression is invalid. */
    public static Long evaluate(String content) {
        if (!looksLikeAttempt(content)) return null;
        MathExpression p = new MathExpression(content.strip());
        try {
            long value = p.expr();
            p.skipWhitespace();
            return p.pos == p.src.length() ? value : null;
        } catch (ArithmeticException | IllegalStateException e) {
            return null;
        }
    }

    // ---------------- parser ----------------

    private long expr() {
        long value = term();
        while (true) {
            char op = peek();
            if (op == '+') { pos++; value = Math.addExact(value, term()); }
            else if (op == '-') { pos++; value = Math.subtractExact(value, term()); }
            else return value;
        }
    }

    private long term() {
        long value = factor();
        while (true) {
            char op = peek();
            if (op == '*' || op == 'x' || op == '×') {
                pos++;
                value = Math.multiplyExact(value, factor());
            } else if (op == '/' || op == '÷') {
                pos++;
                long divisor = factor();
                if (divisor == 0 || value % divisor != 0) {
                    throw new ArithmeticException("inexact division");
                }
                value /= divisor;
            } else if (op == '%') {
                pos++;
                long divisor = factor();
                if (divisor == 0) throw new ArithmeticException("mod zero");
                value %= divisor;
            } else {
                return value;
            }
        }
    }

    private long factor() {
        long base = unary();
        if (peek() == '^') {
            pos++;
            long exponent = factor();
            return pow(base, exponent);
        }
        return base;
    }

    private long unary() {
        char c = peek();
        if (c == '-') {
            pos++;
            return Math.negateExact(unary());
        }
        if (c == '(') {
            pos++;
            long value = expr();
            if (peek() != ')') throw new IllegalStateException("missing )");
            pos++;
            return value;
        }
        if (c >= '0' && c <= '9') {
            return number();
        }
        throw new IllegalStateException("unexpected char at " + pos);
    }

    private long number() {
        int start = pos;
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        try {
            return Long.parseLong(src, start, pos, 10);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("number too large");
        }
    }

    private static long pow(long base, long exponent) {
        if (exponent < 0 || exponent > MAX_EXPONENT) {
            throw new ArithmeticException("exponent out of range");
        }
        long result = 1;
        for (long i = 0; i < exponent; i++) {
            result = Math.multiplyExact(result, base);
        }
        return result;
    }

    private void skipWhitespace() {
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) pos++;
    }

    /** Skips whitespace, then returns the current char without consuming it ({@code \0} at end). */
    private char peek() {
        skipWhitespace();
        return pos < src.length() ? src.charAt(pos) : '\0';
    }
}
