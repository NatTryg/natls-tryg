package org.amshove.natparse.parsing;

import org.amshove.natparse.lexing.Lexer;
import org.amshove.natparse.lexing.SyntaxKind;
import org.amshove.natparse.lexing.SyntaxToken;
import org.amshove.natparse.natural.IReferencableNode;
import org.amshove.natparse.natural.IStatementListNode;
import org.amshove.natparse.natural.ISymbolReferenceNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

class StatementListParser extends AbstractParser<IStatementListNode>
{
	private List<ISymbolReferenceNode> unresolvedReferences;
	private List<IReferencableNode> referencableNodes;

	public List<IReferencableNode> getReferencableNodes()
	{
		return referencableNodes;
	}

	StatementListParser(IModuleProvider moduleProvider)
	{
		super(moduleProvider);
	}

	public List<ISymbolReferenceNode> getUnresolvedReferences()
	{
		return unresolvedReferences;
	}

	@Override
	protected IStatementListNode parseInternal()
	{
		unresolvedReferences = new ArrayList<>();
		referencableNodes = new ArrayList<>();
		var statementList = statementList();
		resolveUnresolvedInternalPerforms();
		if (!shouldRelocateDiagnostics())
		{
			// If diagnostics should be relocated, we're a copycode. So let the includer resolve it themselves.
			resolveUnresolvedExternalPerforms();
		}
		return statementList;
	}

	private StatementListNode statementList()
	{
		var statementList = new StatementListNode();
		while (!tokens.isAtEnd())
		{
			try
			{
				switch (tokens.peek().kind())
				{
					case CALLNAT:
						statementList.addStatement(callnat());
						break;
					case INCLUDE:
						statementList.addStatement(include());
						break;
					case FETCH:
						statementList.addStatement(fetch());
						break;
					case IDENTIFIER:
					case IDENTIFIER_OR_KEYWORD:
						statementList.addStatement(identifierReference());
						break;
					case END:
						statementList.addStatement(end());
						break;
					case DEFINE:
						if (peekAny(1, List.of(SyntaxKind.WINDOW, SyntaxKind.WORK, SyntaxKind.PRINTER, SyntaxKind.FUNCTION, SyntaxKind.DATA, SyntaxKind.PROTOTYPE)))
						{
							tokens.advance();
							tokens.advance();
							break;
						}
						statementList.addStatement(subroutine());
						break;
					case END_IF:
					case END_SUBROUTINE:
						return statementList;
					case IGNORE:
						statementList.addStatement(ignore());
						break;
					case PERFORM:
						if(peek(1).kind() == SyntaxKind.BREAK)
						{
							tokens.advance();
							break;
						}
						statementList.addStatement(perform());
						break;
					case IF:
						statementList.addStatement(ifStatement());
						break;
					default:
						// While the parser is incomplete, we just add a node for every token
						var tokenStatementNode = new SyntheticTokenStatementNode();
						consume(tokenStatementNode);
						statementList.addStatement(tokenStatementNode);
				}
			}
			catch (ParseError e)
			{
				// Currently just skip over the token
				// TODO: Add a diagnostic. For this move the method from DefineDataParser up into AbstractParser
				tokens.advance();
			}
		}

		return statementList;
	}

	private StatementNode perform() throws ParseError
	{
		var internalPerform = new InternalPerformNode();
		unresolvedReferences.add(internalPerform);

		consumeMandatory(internalPerform, SyntaxKind.PERFORM);
		var symbolName = identifier();
		var referenceNode = new SymbolReferenceNode(symbolName);
		internalPerform.setReferenceNode(referenceNode);
		internalPerform.addNode(referenceNode);

		return internalPerform;
	}

	private StatementNode ignore() throws ParseError
	{
		var ignore = new IgnoreNode();
		consumeMandatory(ignore, SyntaxKind.IGNORE);
		return ignore;
	}

	private StatementNode subroutine() throws ParseError
	{
		var subroutine = new SubroutineNode();
		consumeMandatory(subroutine, SyntaxKind.DEFINE);
		consumeOptionally(subroutine, SyntaxKind.SUBROUTINE);
		var nameToken = consumeMandatoryIdentifier(subroutine);
		subroutine.setName(nameToken);

		subroutine.setBody(statementList());

		consumeMandatory(subroutine, SyntaxKind.END_SUBROUTINE);

		referencableNodes.add(subroutine);

		return subroutine;
	}

	private StatementNode end() throws ParseError
	{
		var endNode = new EndNode();
		consumeMandatory(endNode, SyntaxKind.END);
		return endNode;
	}

	private StatementNode identifierReference() throws ParseError
	{
		var token = identifier();
		if(peekKind( SyntaxKind.LPAREN)
				&& (peekKind(1, SyntaxKind.LESSER_SIGN) || peekKind(1, SyntaxKind.LESSER_GREATER)))
		{
			return functionCall(token);
		}

		var node = new SymbolReferenceNode(token);
		unresolvedReferences.add(node);
		return new SyntheticVariableStatementNode(node);
	}

	private FunctionCallNode functionCall(SyntaxToken token) throws ParseError
	{
		var node = new FunctionCallNode();

		var functionName = new TokenNode(token);
		node.setReferencingToken(token);
		node.addNode(functionName);
		var module = sideloadModule(token.symbolName(), functionName);
		node.setReferencedModule((NaturalModule) module);

		consumeMandatory(node, SyntaxKind.LPAREN);

		while(!peekKind(SyntaxKind.RPAREN))
		{
			if(peekKind(SyntaxKind.IDENTIFIER_OR_KEYWORD) || peekKind(SyntaxKind.IDENTIFIER))
			{
				node.addNode(identifierReference());
			}
			else
			{
				consume(node);
			}
		}

		consumeMandatory(node, SyntaxKind.RPAREN);

		return node;
	}

	private CallnatNode callnat() throws ParseError
	{
		var callnat = new CallnatNode();

		consumeMandatory(callnat, SyntaxKind.CALLNAT);

		if (isNotCallnatOrFetchModule())
		{
			report(ParserErrors.unexpectedToken(List.of(SyntaxKind.STRING_LITERAL, SyntaxKind.IDENTIFIER), peek()));
		}

		if (consumeEitherOptionally(callnat, SyntaxKind.IDENTIFIER, SyntaxKind.IDENTIFIER_OR_KEYWORD))
		{
			callnat.setReferencingToken(previousToken());
		}

		if (consumeOptionally(callnat, SyntaxKind.STRING_LITERAL))
		{
			callnat.setReferencingToken(previousToken());
			var referencedModule = sideloadModule(callnat.referencingToken().stringValue().toUpperCase().trim(), previousTokenNode());
			callnat.setReferencedModule((NaturalModule) referencedModule);
		}

		return callnat;
	}

	private IncludeNode include() throws ParseError
	{
		var include = new IncludeNode();

		consumeMandatory(include, SyntaxKind.INCLUDE);

		var referencingToken = consumeMandatoryIdentifier(include);
		include.setReferencingToken(referencingToken);

		var referencedModule = sideloadModule(referencingToken.symbolName(), previousTokenNode());
		include.setReferencedModule((NaturalModule) referencedModule);

		if (referencedModule != null)
		{
			try
			{
				var includedSource = Files.readString(referencedModule.file().getPath());
				var lexer = new Lexer();
				lexer.relocateDiagnosticPosition(referencingToken);
				var tokens = lexer.lex(includedSource, referencedModule.file().getPath());

				for (var diagnostic : tokens.diagnostics())
				{
					report(diagnostic);
				}

				var nestedParser = new StatementListParser(moduleProvider);
				nestedParser.relocateDiagnosticPosition(
					shouldRelocateDiagnostics()
						? relocatedDiagnosticPosition
						: referencingToken
				);
				var statementList = nestedParser.parse(tokens);

				for (var diagnostic : statementList.diagnostics())
				{
					if (ParserError.isUnresolvedError(diagnostic.id()))
					{
						// Unresolved references will be resolved by the module including the copycode.
						report(diagnostic);
					}
				}
				unresolvedReferences.addAll(nestedParser.unresolvedReferences);
				referencableNodes.addAll(nestedParser.referencableNodes);
				include.setBody(statementList.result(),
					shouldRelocateDiagnostics()
						? relocatedDiagnosticPosition
						: referencingToken
				);
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}
		else
		{
			var unresolvedBody = new StatementListNode();
			unresolvedBody.setParent(include);
			include.setBody(unresolvedBody,
				shouldRelocateDiagnostics()
					? relocatedDiagnosticPosition
					: referencingToken
			);
		}

		return include;
	}

	private FetchNode fetch() throws ParseError
	{
		var fetch = new FetchNode();

		consumeMandatory(fetch, SyntaxKind.FETCH);

		consumeEitherOptionally(fetch, SyntaxKind.RETURN, SyntaxKind.REPEAT);

		if (isNotCallnatOrFetchModule())
		{
			report(ParserErrors.unexpectedToken(List.of(SyntaxKind.STRING_LITERAL, SyntaxKind.IDENTIFIER), peek()));
		}

		if (consumeEitherOptionally(fetch, SyntaxKind.IDENTIFIER, SyntaxKind.IDENTIFIER_OR_KEYWORD))
		{
			fetch.setReferencingToken(previousToken());
		}

		if (consumeOptionally(fetch, SyntaxKind.STRING_LITERAL))
		{
			fetch.setReferencingToken(previousToken());
			var referencedModule = sideloadModule(fetch.referencingToken().stringValue().toUpperCase().trim(), previousTokenNode());
			fetch.setReferencedModule((NaturalModule) referencedModule);
		}

		return fetch;
	}

	private IfStatementNode ifStatement() throws ParseError
	{
		var ifStatement = new IfStatementNode();

		consumeMandatory(ifStatement, SyntaxKind.IF);

		ifStatement.setBody(statementList());

		consumeMandatory(ifStatement, SyntaxKind.END_IF);

		return ifStatement;
	}

	private boolean isNotCallnatOrFetchModule()
	{
		return !peekKind(SyntaxKind.STRING_LITERAL) && !peekKind(SyntaxKind.IDENTIFIER) && !peekKind(SyntaxKind.IDENTIFIER_OR_KEYWORD);
	}

	private void resolveUnresolvedExternalPerforms()
	{
		var resolvedReferences = new ArrayList<ISymbolReferenceNode>();

		for (var unresolvedReference : unresolvedReferences)
		{
			if (unresolvedReference instanceof InternalPerformNode internalPerformNode)
			{
				var foundModule = sideloadModule(unresolvedReference.token().trimmedSymbolName(32), internalPerformNode.tokenNode());
				if (foundModule != null)
				{
					var externalPerform = new ExternalPerformNode(((InternalPerformNode) unresolvedReference));
					((BaseSyntaxNode) unresolvedReference.parent()).replaceChild((BaseSyntaxNode) unresolvedReference, externalPerform);
					externalPerform.setReference(foundModule);
				}

				// We mark the reference as resolved even though it might not be found.
				// We do this, because the `sideloadModule` already reports a diagnostic.
				resolvedReferences.add(unresolvedReference);
			}
		}

		unresolvedReferences.removeAll(resolvedReferences);
	}

	private void resolveUnresolvedInternalPerforms()
	{
		var resolvedReferences = new ArrayList<ISymbolReferenceNode>();
		for (var referencableNode : referencableNodes)
		{
			for (var unresolvedReference : unresolvedReferences)
			{
				if (!(unresolvedReference instanceof InternalPerformNode))
				{
					continue;
				}

				var unresolvedPerformName = unresolvedReference.token().trimmedSymbolName(32);
				if (unresolvedPerformName.equals(referencableNode.declaration().trimmedSymbolName(32)))
				{
					referencableNode.addReference(unresolvedReference);
					resolvedReferences.add(unresolvedReference);
				}
			}
		}

		unresolvedReferences.removeAll(resolvedReferences);
	}
}
