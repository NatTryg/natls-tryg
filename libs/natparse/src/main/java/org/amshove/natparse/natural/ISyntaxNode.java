package org.amshove.natparse.natural;

import org.amshove.natparse.IPosition;

import java.nio.file.Path;

public interface ISyntaxNode extends ISyntaxTree
{
	ISyntaxNode parent();

	IPosition position();

	IPosition diagnosticPosition();

	default boolean enclosesPosition(int line, int column)
	{
		return diagnosticPosition().line() == line
			&& descendants().first().diagnosticPosition().offsetInLine() <= column
			&& descendants().last().diagnosticPosition().endOffset() >= column;
	}

	boolean isInFile(Path path);

	/**
	 * Clean up resources that may leak, like references. Called when a Node is no longer valid.
	 */
	void destroy();
}
