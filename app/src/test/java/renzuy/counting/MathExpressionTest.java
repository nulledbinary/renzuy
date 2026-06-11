package renzuy.counting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MathExpressionTest {

    @Test
    void plainNumbers() {
        assertEquals(77L, MathExpression.evaluate("77"));
        assertEquals(1L, MathExpression.evaluate(" 1 "));
        assertEquals(0L, MathExpression.evaluate("0"));
    }

    @Test
    void equationsFromTheSpec() {
        // user 1 is on 76, next user sends 47+30 → 77, still valid
        assertEquals(77L, MathExpression.evaluate("47+30"));
        assertEquals(77L, MathExpression.evaluate("47 + 30"));
    }

    @Test
    void operatorsAndPrecedence() {
        assertEquals(7L, MathExpression.evaluate("1+2*3"));
        assertEquals(9L, MathExpression.evaluate("(1+2)*3"));
        assertEquals(8L, MathExpression.evaluate("2^3"));
        assertEquals(512L, MathExpression.evaluate("2^3^2"));    // right-assoc
        assertEquals(47L, MathExpression.evaluate("94/2"));
        assertEquals(2L, MathExpression.evaluate("12%5"));
        assertEquals(50L, MathExpression.evaluate("5x10"));
        assertEquals(50L, MathExpression.evaluate("5×10"));
        assertEquals(50L, MathExpression.evaluate("100÷2"));
        assertEquals(-3L, MathExpression.evaluate("-(1+2)"));
        assertEquals(5L, MathExpression.evaluate("-5+10"));
    }

    @Test
    void inexactAndIllegalArithmeticIsInvalid() {
        assertNull(MathExpression.evaluate("5/2"));      // not an integer
        assertNull(MathExpression.evaluate("5/0"));
        assertNull(MathExpression.evaluate("5%0"));
        assertNull(MathExpression.evaluate("2^-1"));
        assertNull(MathExpression.evaluate("2^64"));     // exponent cap
        assertNull(MathExpression.evaluate("9223372036854775807+1")); // overflow
        assertNull(MathExpression.evaluate("99999999999999999999")); // > long
    }

    @Test
    void malformedExpressionsAreInvalid() {
        assertNull(MathExpression.evaluate("77+"));
        assertNull(MathExpression.evaluate("(77"));
        assertNull(MathExpression.evaluate("77)"));
        assertNull(MathExpression.evaluate("1 2"));      // juxtaposition
        assertNull(MathExpression.evaluate("++1"));
        assertNull(MathExpression.evaluate(""));
        assertNull(MathExpression.evaluate(null));
    }

    @Test
    void chatterIsNotAnAttempt() {
        assertFalse(MathExpression.looksLikeAttempt("hello"));
        assertFalse(MathExpression.looksLikeAttempt("i got 99 problems"));
        assertFalse(MathExpression.looksLikeAttempt("100%!"));
        assertFalse(MathExpression.looksLikeAttempt("🎉"));
        assertFalse(MathExpression.looksLikeAttempt("https://example.com/1"));
        assertFalse(MathExpression.looksLikeAttempt("x"));   // no digits
        assertFalse(MathExpression.looksLikeAttempt("12.5")); // decimals not allowed
        assertFalse(MathExpression.looksLikeAttempt("9".repeat(MathExpression.MAX_LENGTH + 1)));
    }

    @Test
    void numericMessagesAreAttempts() {
        assertTrue(MathExpression.looksLikeAttempt("77"));
        assertTrue(MathExpression.looksLikeAttempt("47+30"));
        assertTrue(MathExpression.looksLikeAttempt("(99)"));
        assertTrue(MathExpression.looksLikeAttempt("2 ^ 5"));
    }
}
