package org.amshove.natparse.natural;

import org.amshove.natparse.ReadOnlyList;
import org.amshove.natparse.natural.output.IOutputElementNode;

public interface IOutputStatementNode extends IStatementNode, ICanHaveReportSpecification
{
	ReadOnlyList<IAttributeNode> statementAttributes();

	ReadOnlyList<IOutputElementNode> operands();
}
