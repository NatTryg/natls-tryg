package org.amshove.natparse.natural;

public interface IReadNode extends IStatementWithBodyNode
{
	IVariableReferenceNode viewReference();

	ReadSequence readSequence();
}