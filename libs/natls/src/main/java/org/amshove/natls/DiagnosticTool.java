package org.amshove.natls;

public enum DiagnosticTool
{
	NATPARSE("NatParse"),
	NATUNIT("NatUnit");

	private final String id;

	DiagnosticTool(String id)
	{
		this.id = id;
	}

	public String getId()
	{
		return id;
	}
}
