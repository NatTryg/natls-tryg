package org.amshove.natparse.lexing;

import org.amshove.natparse.IDiagnostic;
import org.amshove.natparse.ReadOnlyList;
import org.amshove.natparse.natural.project.NaturalHeader;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

public class TokenList implements Iterable<SyntaxToken>
{
	public static TokenList fromTokens(Path filePath, List<SyntaxToken> tokenList)
	{
		return new TokenList(filePath, tokenList);
	}

	public static TokenList fromTokensAndDiagnostics(
		Path filePath, List<SyntaxToken> tokenList,
		List<LexerDiagnostic> diagnostics, List<SyntaxToken> comments, NaturalHeader sourceHeader
	)
	{
		return new TokenList(filePath, tokenList, diagnostics, comments, sourceHeader);
	}

	private final ReadOnlyList<SyntaxToken> tokens;
	private final List<LexerDiagnostic> diagnostics;
	private final List<SyntaxToken> comments;
	private final Path filePath;
	private NaturalHeader sourceHeader;
	private int currentOffset = 0;

	TokenList(Path filePath, List<SyntaxToken> tokens)
	{
		this.tokens = ReadOnlyList.from(tokens);
		diagnostics = List.of();
		comments = List.of();
		this.filePath = filePath;
	}

	TokenList(Path filePath, List<SyntaxToken> tokens, List<LexerDiagnostic> diagnostics, List<SyntaxToken> comments, NaturalHeader sourceHeader)
	{
		this.tokens = ReadOnlyList.from(tokens);
		this.diagnostics = diagnostics;
		this.comments = comments;
		this.filePath = filePath;
		this.sourceHeader = sourceHeader;
	}

	public ReadOnlyList<IDiagnostic> diagnostics()
	{
		return ReadOnlyList.from(diagnostics.stream().map(d -> (IDiagnostic) d).toList()); // TODO: Perf
	}

	public Path filePath()
	{
		return filePath;
	}

	/**
	 * Peeks the next token.
	 */
	public SyntaxToken peek()
	{
		return peek(0);
	}

	/**
	 * Peeks the token `offset` times ahead.
	 */
	public SyntaxToken peek(int offset)
	{
		var index = currentOffset + offset;
		if (exceedsEnd(index))
		{
			return null;
		}
		return tokens.get(index);
	}

	/**
	 * Peeks the token kinds of the following tokens and returns true if they're in the given order.<br/>
	 * Returns false if either the order or the amount of following tokens doesn't match.
	 */
	public boolean peekKinds(SyntaxKind... kinds)
	{
		if (isAtEnd(kinds.length - 1))
		{
			return false;
		}

		for (var offset = 0; offset < kinds.length; offset++)
		{
			if (peek(offset).kind() != kinds[offset])
			{
				return false;
			}
		}

		return true;
	}

	/**
	 * Advances over the current token.
	 */
	public SyntaxToken advance()
	{
		var token = peek();
		currentOffset++;
		return token;
	}

	/**
	 * Resets the position offset times back.
	 */
	public void rollback(int offset)
	{
		currentOffset -= offset;
	}

	public boolean isAtEnd()
	{
		return exceedsEnd(currentOffset);
	}

	/**
	 * Checks if the given offset relative to the current offset is out of bounds.
	 *
	 * @param offset - The offset added to the current position
	 * @return true if end is passed, false if in bounds
	 */
	public boolean isAtEnd(int offset)
	{
		return exceedsEnd(currentOffset + offset);
	}

	private boolean exceedsEnd(int totalOffset)
	{
		return totalOffset >= tokens.size() || totalOffset < 0;
	}

	public int size()
	{
		return tokens.size();
	}

	public ReadOnlyList<SyntaxToken> allTokens()
	{
		return tokens;
	}

	public boolean advanceAfterNext(SyntaxKind kind)
	{
		if (advanceUntil(kind))
		{
			advance();
			return !isAtEnd();
		}

		return false;
	}

	public boolean advanceUntil(SyntaxKind kind)
	{
		while (!isAtEnd() && peek().kind() != kind)
		{
			advance();
		}

		return !isAtEnd();
	}

	public boolean advanceAfterNextIfFound(SyntaxKind kind)
	{
		int index = currentOffset;
		while (!exceedsEnd(index))
		{
			if (peek(index).kind() == kind)
			{
				setCurrentOffset(index);
				return advanceAfterNext(kind);
			}
			index++;
		}
		return false;
	}

	public ReadOnlyList<SyntaxToken> comments()
	{
		return ReadOnlyList.from(comments); // TODO: Perf
	}

	public NaturalHeader sourceHeader()
	{
		return sourceHeader;
	}

	/**
	 * Consumes the current token if it matches the kind and then advances.
	 */
	public boolean consume(SyntaxKind kind)
	{
		if (!isAtEnd() && peek().kind() == kind)
		{
			advance();
			return true;
		}

		return false;
	}

	public int getCurrentOffset()
	{
		return currentOffset;
	}

	/**
	 * Returns all tokens from start to end.
	 *
	 * @param start Inclusive index of the first token.
	 * @param end Inclusive index of the last token.
	 */
	public ReadOnlyList<SyntaxToken> subrange(int start, int end)
	{
		return tokens.subList(start, end + 1);
	}

	public Stream<SyntaxToken> stream()
	{
		return tokens.stream();
	}

	private void setCurrentOffset(int newOffset)
	{
		if (!exceedsEnd(newOffset))
		{
			currentOffset = newOffset;
		}
	}

	/**
	 * Resets the token index to 0.
	 */
	public void rollback()
	{
		currentOffset = 0;
	}

	@Override
	public Iterator<SyntaxToken> iterator()
	{
		return tokens.iterator();
	}

	/**
	 * Advances the current offset in an unsafe manner. <strong>This does not check if the offset goes out of
	 * bounds!</strong>
	 */
	public void advanceBy(int offset)
	{
		currentOffset += offset;
	}
}
