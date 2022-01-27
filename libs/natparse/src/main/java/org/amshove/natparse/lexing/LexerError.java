package org.amshove.natparse.lexing;

public enum LexerError
{
	UNKNOWN_CHARACTER("NPL001"),
	UNTERMINATED_STRING("NPL002");

	private final String id;

	LexerError(String id)
	{
		this.id = id;
	}

	public String id()
	{
		return id;
	}
}