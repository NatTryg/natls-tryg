package org.amshove.natparse.lexing;

import org.junit.jupiter.api.Test;

public class LexerForCommentsShould extends AbstractLexerTest
{
	@Test
	void lexSingleAsteriskComments()
	{
		assertTokens("* Hello from comment", token(SyntaxKind.COMMENT, "* Hello from comment"));
	}

	@Test
	void lexInlineComment()
	{
		assertTokens("   /* Inline comment!", token(SyntaxKind.COMMENT, "/* Inline comment!"));
	}

	@Test
	void lexInlineCommentBeforeLinebreak()
	{
		assertTokens("GT\n/*INCOMMENT\nGT",
			token(SyntaxKind.GT),
			token(SyntaxKind.COMMENT, "/*INCOMMENT"),
			token(SyntaxKind.GT));
	}
}
