package org.amshove.natparse.parsing;

import org.amshove.natparse.lexing.SyntaxKind;
import org.amshove.natparse.lexing.SyntaxToken;
import org.amshove.natparse.natural.*;
import org.amshove.testhelpers.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@IntegrationTest
class StatementListParserShould extends AbstractParserTest<IStatementListNode>
{
	protected StatementListParserShould()
	{
		super(StatementListParser::new);
	}

	@Test
	void parseASimpleCallnat()
	{
		ignoreModuleProvider();
		var callnat = assertParsesSingleStatement("CALLNAT 'MODULE'", ICallnatNode.class);
		assertThat(callnat.referencingToken().kind()).isEqualTo(SyntaxKind.STRING_LITERAL);
		assertThat(callnat.referencingToken().stringValue()).isEqualTo("MODULE");
	}

	@Test
	void raiseADiagnosticWhenNoModuleIsPassed()
	{
		ignoreModuleProvider();
		assertDiagnostic("CALLNAT 1", ParserError.UNEXPECTED_TOKEN);
	}

	@Test
	void allowVariablesAsModuleReferences()
	{
		ignoreModuleProvider();
		var callnat = assertParsesSingleStatement("CALLNAT #THE-SUBPROGRAM", ICallnatNode.class);
		assertThat(callnat.referencingToken().kind()).isEqualTo(SyntaxKind.IDENTIFIER);
		assertThat(callnat.referencingToken().symbolName()).isEqualTo("#THE-SUBPROGRAM");
		assertThat(callnat.reference()).isNull();
	}

	@Test
	void addBidirectionalReferencesForCallnats()
	{
		var calledSubprogram = new NaturalModule(null);
		moduleProvider.addModule("A-MODULE", calledSubprogram);

		var callnat = assertParsesSingleStatement("CALLNAT 'A-MODULE'", ICallnatNode.class);
		assertThat(callnat.reference()).isEqualTo(calledSubprogram);
		assertThat(calledSubprogram.callers()).contains(callnat);
	}

	@Test
	void allowTrailingSpacesInModuleNamesThatAreInStrings()
	{
		var calledSubprogram = new NaturalModule(null);
		moduleProvider.addModule("A-MODULE", calledSubprogram);

		var callnat = assertParsesSingleStatement("CALLNAT 'A-MODULE ' ", ICallnatNode.class);
		assertThat(callnat.reference()).isEqualTo(calledSubprogram);
		assertThat(calledSubprogram.callers()).contains(callnat);
	}

	@Test
	void findCalledSubprogramsWhenSourceContainsLowerCaseCharacters()
	{
		var calledSubprogram = new NaturalModule(null);
		moduleProvider.addModule("A-MODULE", calledSubprogram);

		var callnat = assertParsesSingleStatement("CALLNAT 'A-module'", ICallnatNode.class);
		assertThat(callnat.reference()).isEqualTo(calledSubprogram);
		assertThat(calledSubprogram.callers()).contains(callnat);
	}

	@Test
	void parseASimpleInclude()
	{
		ignoreModuleProvider();
		var include = assertParsesSingleStatement("INCLUDE L4NLOGIT", IIncludeNode.class);
		assertThat(include.referencingToken().kind()).isEqualTo(SyntaxKind.IDENTIFIER);
		assertThat(include.referencingToken().symbolName()).isEqualTo("L4NLOGIT");
	}

	@Test
	void raiseADiagnosticWhenNoCopycodeIsPassed()
	{
		assertDiagnostic("INCLUDE 1", ParserError.UNEXPECTED_TOKEN);
	}

	@Test
	void parseASimpleFetch()
	{
		ignoreModuleProvider();
		var fetch = assertParsesSingleStatement("FETCH 'PROG'", IFetchNode.class);
		assertThat(fetch.referencingToken().kind()).isEqualTo(SyntaxKind.STRING_LITERAL);
		assertThat(fetch.referencingToken().stringValue()).isEqualTo("PROG");
	}

	@Test
	void parseASimpleFetchReturn()
	{
		ignoreModuleProvider();
		var fetch = assertParsesSingleStatement("FETCH RETURN 'PROG'", IFetchNode.class);
		assertThat(fetch.referencingToken().kind()).isEqualTo(SyntaxKind.STRING_LITERAL);
		assertThat(fetch.referencingToken().stringValue()).isEqualTo("PROG");
	}

	@Test
	void parseASimpleFetchRepeat()
	{
		ignoreModuleProvider();
		var fetch = assertParsesSingleStatement("FETCH REPEAT 'PROG'", IFetchNode.class);
		assertThat(fetch.referencingToken().kind()).isEqualTo(SyntaxKind.STRING_LITERAL);
		assertThat(fetch.referencingToken().stringValue()).isEqualTo("PROG");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"",
		"REPEAT",
		"RETURN"
	})
	void parseAFetchWithVariables(String fetchType)
	{
		ignoreModuleProvider();
		var fetch = assertParsesSingleStatement("FETCH %s #MYVAR".formatted(fetchType), IFetchNode.class);
		assertThat(fetch.referencingToken().kind()).isEqualTo(SyntaxKind.IDENTIFIER);
		assertThat(fetch.referencingToken().symbolName()).isEqualTo("#MYVAR");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"",
		"REPEAT",
		"RETURN"
	})
	void parseAFetchWithQualifiedVariables(String fetchType)
	{
		ignoreModuleProvider();
		var fetch = assertParsesSingleStatement("FETCH %s #MYGROUP.#MYVAR".formatted(fetchType), IFetchNode.class);
		assertThat(fetch.referencingToken().kind()).isEqualTo(SyntaxKind.IDENTIFIER);
		assertThat(fetch.referencingToken().symbolName()).isEqualTo("#MYGROUP.#MYVAR");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"",
		"RETURN",
		"REPEAT"
	})
	void resolveExternalModulesForAFetchStatement(String fetchSource)
	{
		var program = new NaturalModule(null);
		moduleProvider.addModule("PROG", program);

		var fetch = assertParsesSingleStatement("FETCH %s 'PROG'".formatted(fetchSource), IFetchNode.class);
		assertThat(fetch.reference()).isEqualTo(program);
	}

	@Test
	void parseAnEndNode()
	{
		var endNode = assertParsesSingleStatement("END", IEndNode.class);
		assertThat(endNode.descendants()).isNotEmpty();
	}

	@Test
	void parseIgnore()
	{
		assertParsesSingleStatement("IGNORE", IIgnoreNode.class);
	}

	@Test
	void parseASubroutine()
	{
		var subroutine = assertParsesSingleStatement("""
			   DEFINE SUBROUTINE MY-SUBROUTINE
			       IGNORE
			   END-SUBROUTINE
			""", ISubroutineNode.class);

		assertThat(subroutine.declaration().symbolName()).isEqualTo("MY-SUBROUTINE");
		assertThat(subroutine.references()).isEmpty();
		assertThat(subroutine.body().statements()).hasSize(1);
	}

	@Test
	void parseASubroutineWithoutSubroutineKeyword()
	{
		var subroutine = assertParsesSingleStatement("""
			   DEFINE #MY-SUBROUTINE
			       IGNORE
			   END-SUBROUTINE
			""", ISubroutineNode.class);

		assertThat(subroutine.declaration().symbolName()).isEqualTo("#MY-SUBROUTINE");
	}

	@Test
	void parseInternalPerformNodes()
	{
		ignoreModuleProvider();
		var perform = assertParsesSingleStatement("PERFORM MY-SUBROUTINE", IInternalPerformNode.class);
		assertThat(perform.token().symbolName()).isEqualTo("MY-SUBROUTINE");
		assertThat(perform.reference()).isNull();
	}

	@Test
	void parseInternalPerformNodesWithReference()
	{
		var statements = assertParsesWithoutDiagnostics("""
			DEFINE SUBROUTINE MY-SUBROUTINE
				IGNORE
			END-SUBROUTINE

			PERFORM MY-SUBROUTINE
			""");

		assertThat(statements.statements()).hasSize(2);
		var subroutine = statements.statements().get(0);
		var perform = assertNodeType(statements.statements().get(1), IInternalPerformNode.class);

		assertThat(perform.token().symbolName()).isEqualTo("MY-SUBROUTINE");
		assertThat(perform.reference()).isEqualTo(subroutine);
	}

	@Test
	void resolveInternalSubroutinesWithLongNames()
	{
		var statements = assertParsesWithoutDiagnostics("""
			DEFINE SUBROUTINE THIS-HAS-MORE-THAN-THIRTY-TWO-CHARACTERS
				IGNORE
			END-SUBROUTINE

			PERFORM THIS-HAS-MORE-THAN-THIRTY-TWO-CHARACTERS-BUT-IT-WORKS-I-SHOULD-NEVER-DO-THAT
			""");

		assertThat(statements.statements()).hasSize(2);
		var subroutine = statements.statements().get(0);
		var perform = assertNodeType(statements.statements().get(1), IInternalPerformNode.class);

		assertThat(perform.token().symbolName()).isEqualTo("THIS-HAS-MORE-THAN-THIRTY-TWO-CHARACTERS-BUT-IT-WORKS-I-SHOULD-NEVER-DO-THAT");
		assertThat(perform.token().trimmedSymbolName(32)).isEqualTo("THIS-HAS-MORE-THAN-THIRTY-TWO-CH");
		assertThat(perform.reference()).isEqualTo(subroutine);
	}

	@Test
	void parseInternalPerformNodesWithReferenceWhenSubroutineIsDefinedAfter()
	{
		var statements = assertParsesWithoutDiagnostics("""
			PERFORM MY-SUBROUTINE

			DEFINE SUBROUTINE MY-SUBROUTINE
				IGNORE
			END-SUBROUTINE
			""");

		assertThat(statements.statements()).hasSize(2);
		var perform = assertNodeType(statements.statements().get(0), IInternalPerformNode.class);
		var subroutine = statements.statements().get(1);

		assertThat(perform.token().symbolName()).isEqualTo("MY-SUBROUTINE");
		assertThat(perform.reference()).isEqualTo(subroutine);
	}

	@Test
	void notExportResolvedPerformCallsAsUnresolved()
	{
		var statements = assertParsesWithoutDiagnostics("""
			PERFORM MY-SUBROUTINE

			DEFINE SUBROUTINE MY-SUBROUTINE
				IGNORE
			END-SUBROUTINE
			""");

		assertThat(statements.statements()).hasSize(2);
		assertThat(((StatementListParser) sut).getUnresolvedReferences()).isEmpty();
	}

	@Test
	void parseExternalPerformCalls()
	{
		var calledSubroutine = new NaturalModule(null);
		moduleProvider.addModule("EXTERNAL-SUB", calledSubroutine);

		var perform = assertParsesSingleStatement("PERFORM EXTERNAL-SUB", IExternalPerformNode.class);
		assertThat(perform.reference()).isEqualTo(calledSubroutine);
		assertThat(calledSubroutine.callers()).contains(perform);
	}

	@Test
	void parseAFunctionCallWithoutParameter()
	{
		var calledFunction = new NaturalModule(null);
		moduleProvider.addModule("ISSTH", calledFunction);

		var call = assertParsesSingleStatement("ISSTH(<>)", IFunctionCallNode.class);
		assertThat(call.reference()).isEqualTo(calledFunction);
		assertThat(calledFunction.callers()).contains(call);
	}

	@Test
	void parseAFunctionCallWithParameter()
	{
		var calledFunction = new NaturalModule(null);
		moduleProvider.addModule("ISSTH", calledFunction);

		var call = assertParsesSingleStatement("ISSTH(<5>)", IFunctionCallNode.class);
		assertThat(call.reference()).isEqualTo(calledFunction);
		assertThat(calledFunction.callers()).contains(call);
		assertThat(call.position().offsetInLine()).isEqualTo(0);
	}

	@Test
	void distinguishBetweenArrayAccessAndFunctionCallInIfCondition()
	{
		var statementList = assertParsesWithoutDiagnostics("""
			   IF #THE-ARRAY(#THE-VARIABLE) <> 5
			   IGNORE
			   END-IF
			""");

		assertThat(statementList.statements()).noneMatch(s -> s instanceof IFunctionCallNode);
	}

	@Test
	void parseIfStatements()
	{
		var ifStatement = assertParsesSingleStatement("""
			IF #TEST = 5
			    IGNORE
			END-IF
			""", IIfStatementNode.class);

		assertThat(ifStatement.condition()).isNotNull();
		assertThat(ifStatement.body().statements()).hasSize(1);
		assertThat(ifStatement.descendants()).hasSize(4);
	}


	@Test
	void allowIfStatementsToContainTheThenKeyword()
	{
		var ifStatement = assertParsesSingleStatement("""
			IF #TEST = 5 THEN
			    IGNORE
			END-IF
			""", IIfStatementNode.class);

		assertThat(ifStatement.condition().findDescendantToken(SyntaxKind.THEN)).isNull(); // should not be part of the condition
		assertThat(ifStatement.findDescendantToken(SyntaxKind.THEN)).isNotNull(); // but be part of the if statement itself
		assertThat(ifStatement.body().statements()).hasSize(1);
	}

	@Test
	void allowThenAfterMaskInIf()
	{
		assertParsesSingleStatement("""
			IF #TEST = MASK(A...) THEN
			    IGNORE
			END-IF
			""", IIfStatementNode.class);
	}

	@Test
	void parseForColonEqualsToStatements()
	{
		var forLoopNode = assertParsesSingleStatement("""
			FOR #I := 1 TO 10
			    IGNORE
			END-FOR
			""", IForLoopNode.class);

		assertThat(forLoopNode.body().statements()).hasSize(1);
		assertThat(forLoopNode.descendants()).hasSize(8);
	}

	@Test
	void parseAMinimalForLoop()
	{
		var forLoopNode = assertParsesSingleStatement("""
			FOR #I 1 10
			    IGNORE
			END-FOR
			""", IForLoopNode.class);

		assertThat(forLoopNode.body().statements()).hasSize(1);
	}

	@Test
	void parseForEqToStatements()
	{
		var forLoopNode = assertParsesSingleStatement("""
			FOR #I EQ 1 TO 10
			    IGNORE
			END-FOR
			""", IForLoopNode.class);

		assertThat(forLoopNode.body().statements()).hasSize(1);
		assertThat(forLoopNode.descendants()).hasSize(8);
	}

	@Test
	void parseForFromToStatementsStep()
	{
		var forLoopNode = assertParsesSingleStatement("""
			FOR #I FROM 5 TO 10 STEP 2
			    IGNORE
			END-FOR
			""", IForLoopNode.class);

		assertThat(forLoopNode.body().statements()).hasSize(1);
		assertThat(forLoopNode.descendants()).hasSize(10);
	}

	@Test
	void parseForWithoutFromOrEqOrColonEqualsToStatementsStep()
	{
		var forLoopNode = assertParsesSingleStatement("""
			FOR #I 5 TO 10 STEP 2
			    IGNORE
			END-FOR
			""", IForLoopNode.class);

		assertThat(forLoopNode.body().statements()).hasSize(1);
		assertThat(forLoopNode.descendants()).hasSize(9);
	}

	@Test
	void allowSystemFunctionsAsUpperBound()
	{
		var forLoopNode = assertParsesSingleStatement("""
			FOR #I FROM 5 TO *OCC(#ARR)
			    IGNORE
			END-FOR
			""", IForLoopNode.class);

		var upperBound = assertNodeType(forLoopNode.upperBound(), ISystemFunctionNode.class);
		assertThat(upperBound.systemFunction()).isEqualTo(SyntaxKind.OCC);
		assertThat(upperBound.parameter().first()).isInstanceOf(IVariableReferenceNode.class);
		assertThat(forLoopNode.body().statements()).hasSize(1);
		assertThat(forLoopNode.descendants()).hasSize(8);
	}

	@Test
	void rudimentaryParseForFromThruStatementsStep()
	{
		var forLoopNode = assertParsesSingleStatement("""
			FOR #I FROM 5 THRU 10 STEP 5
			    IGNORE
			END-FOR
			""", IForLoopNode.class);

		assertThat(forLoopNode.body().statements()).hasSize(1);
		assertThat(forLoopNode.descendants()).hasSize(10);
	}

	@Test
	void reportADiagnosticForNotClosedIfStatements()
	{
		assertDiagnostic("""
			IF 5 > 2
			    IGNORE
			""", ParserError.UNCLOSED_STATEMENT);
	}

	@Test
	void parseIfNoRecord()
	{
		var noRecNode = assertParsesSingleStatement("""
			IF NO RECORDS FOUND
			    IGNORE
			END-NOREC
			""", IIfNoRecordNode.class);

		assertThat(noRecNode.body().statements()).hasSize(1);
		assertThat(noRecNode.descendants()).hasSize(6);
	}

	@Test
	void parseIfNoRecordWithoutOptionalTokens()
	{
		var noRecNode = assertParsesSingleStatement("""
			IF NO
			    IGNORE
			END-NOREC
			""", IIfNoRecordNode.class);

		assertThat(noRecNode.body().statements()).hasSize(1);
		assertThat(noRecNode.descendants()).hasSize(4);
	}

	@Test
	void parseIfNoRecordWithoutFoundToken()
	{
		var noRecNode = assertParsesSingleStatement("""
			IF NO RECORDS
			    IGNORE
			END-NOREC
			""", IIfNoRecordNode.class);

		assertThat(noRecNode.body().statements()).hasSize(1);
		assertThat(noRecNode.descendants()).hasSize(5);
	}

	@Test
	void parseSetKey()
	{
		var setKey = assertParsesSingleStatement("""
			SET KEY PF1=HELP PF2=PROGRAM
			""", ISetKeyNode.class);

		assertThat(setKey.descendants()).hasSize(8);
	}

	@Test
	void parseSetKeyNamedOff()
	{
		assertParsesSingleStatement("""
			SET KEY NAMED OFF
			""", ISetKeyNode.class);
	}

	@Test
	void parseSetKeyNamed()
	{
		assertParsesSingleStatement("""
			SET KEY PF1 NAMED '-'
			""", ISetKeyNode.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"SET KEY ALL",
		"SET KEY PF2",
		"SET KEY PF2=PGM",
		"SET KEY OFF",
		"SET KEY ON",
		"SET KEY PF2=OFF",
		"SET KEY PF2=ON",
		"SET KEY PF4='SAVE'",
		"SET KEY PF4=#XYX",
		"SET KEY PF6='LIST MAP *'",
		"SET KEY PF2='%%'",
		"SET KEY PF9=' '",
		"SET KEY PF12=DATA 'YES'",
		"SET KEY PF4=COMMAND OFF",
		"SET KEY PF4=COMMAND ON",
		"SET KEY COMMAND OFF",
		"SET KEY COMMAND ON",
		"SET KEY PF1=HELP",
		"SET KEY PF10=DISABLED",
		"SET KEY ENTR NAMED 'EXEC'",
		"SET KEY PF3 NAMED 'EXIT'",
		"SET KEY PF3 NAMED OFF",
		"SET KEY NAMED OFF",
		"SET KEY PF4='AP1' NAMED 'APPL1'"
	})
	void parseSetKeyExamples(String statement)
	{
		assertParsesSingleStatement(statement, ISetKeyNode.class);
	}

	@Test
	void parseFind()
	{
		var findStatement = assertParsesSingleStatement("""
			FIND THE-VIEW WITH THE-DESCRIPTOR = 'Asd'
			    IGNORE
			END-FIND
			""", IFindNode.class);

		assertThat(findStatement.viewReference()).isNotNull();
		assertThat(findStatement.descendants()).anyMatch(n -> n instanceof IDescriptorNode);
		assertThat(findStatement.descendants()).hasSize(8);
	}

	@Test
	void parseFindWithNumberLimit()
	{
		var findStatement = assertParsesSingleStatement("""
			FIND (5) THE-VIEW WITH THE-DESCRIPTOR = 'Asd'
			IGNORE
			END-FIND
			""", IFindNode.class);

		assertThat(findStatement.viewReference()).isNotNull();
		assertThat(findStatement.descendants()).anyMatch(n -> n instanceof IDescriptorNode);
		assertThat(findStatement.descendants()).hasSize(11);
	}

	@Test
	void parseFindWithoutBody()
	{
		var findStatement = assertParsesSingleStatement("""
			FIND FIRST THE-VIEW WITH THE-DESCRIPTOR = 'Asd'
			""", IFindNode.class);

		assertThat(findStatement.viewReference()).isNotNull();
		assertThat(findStatement.descendants()).anyMatch(n -> n instanceof IDescriptorNode);
		assertThat(findStatement.descendants()).hasSize(5);
	}

	@Test
	void parseResetStatements()
	{
		var reset = assertParsesSingleStatement("RESET #THEVAR", IResetStatementNode.class);
		assertThat(reset.operands()).hasSize(1);
	}

	@Test
	void parseResetInitialStatements()
	{
		var reset = assertParsesSingleStatement("RESET INITIAL #THEVAR #THEOTHERVAR", IResetStatementNode.class);
		assertThat(reset.operands()).hasSize(2);
	}

	@Test
	void rudimentaryParseMasks()
	{
		// TODO(expressions): Implement proper expressions
		var mask = assertParsesSingleStatement("MASK (DDMMYYYY)", SyntheticTokenStatementNode.class);
		assertThat(mask).isNotNull();
	}

	@Test
	void parseASimpleDefinePrinter()
	{
		var printer = assertParsesSingleStatement("DEFINE PRINTER(2)", IDefinePrinterNode.class);
		assertThat(printer.printerNumber()).isEqualTo(2);
		assertThat(printer.printerName()).isEmpty();
	}

	@Test
	void parseADefinePrinterWithPrinterName()
	{
		var printer = assertParsesSingleStatement("DEFINE PRINTER(MYPRINTER=5)", IDefinePrinterNode.class);
		assertThat(printer.printerNumber()).isEqualTo(5);
		assertThat(printer.printerName()).map(SyntaxToken::symbolName).hasValue("MYPRINTER");
	}

	@Test
	void parseADefinePrinterWithOutputString()
	{
		var printer = assertParsesSingleStatement("DEFINE PRINTER(5) OUTPUT 'LPT1'", IDefinePrinterNode.class);
		assertThat(printer.output()).hasValueSatisfying(n -> assertThat(n).isInstanceOf(ILiteralNode.class));
		assertThat(printer.output()).map(ILiteralNode.class::cast).map(ILiteralNode::token).map(SyntaxToken::stringValue).hasValue("LPT1");
	}

	@Test
	void reportADiagnosticIfDefinePrinterHasAnInvalidOutputStringFormat()
	{
		assertDiagnostic("DEFINE PRINTER (2) OUTPUT 'WRONG'", ParserError.INVALID_PRINTER_OUTPUT_FORMAT);
	}

	@Test
	void reportADiagnosticIfDefinePrinterHasAnInvalidTokenKind()
	{
		assertDiagnostic("DEFINE PRINTER (2) OUTPUT 5", ParserError.UNEXPECTED_TOKEN);
	}

	@Test
	void parseADefinePrinterWithOutputVariable()
	{
		var printer = assertParsesSingleStatement("DEFINE PRINTER(5) OUTPUT #MYPRINTER", IDefinePrinterNode.class);
		assertThat(printer.output()).hasValueSatisfying(n -> assertThat(n).isInstanceOf(ISymbolReferenceNode.class));
		assertThat(printer.output()).map(ISymbolReferenceNode.class::cast).map(ISymbolReferenceNode::referencingToken).map(SyntaxToken::symbolName).hasValue("#MYPRINTER");
	}

	@Test
	void parseADefinePrinterWithProfile()
	{
		assertParsesWithoutDiagnostics("DEFINE PRINTER(8) PROFILE 'MYPR'");
	}

	@Test
	void parseADefinePrinterWithCopies()
	{
		assertParsesWithoutDiagnostics("DEFINE PRINTER(8) COPIES 10");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"HOLD", "KEEP", "DEL"
	})
	void parseADefinePrinterWithDisp(String disp)
	{
		assertParsesWithoutDiagnostics("DEFINE PRINTER(8) DISP %s".formatted(disp));
	}

	@Test
	void reportADiagnosticIfThePrinterProfileIsLongerThan8()
	{
		assertDiagnostic("DEFINE PRINTER(8) PROFILE 'MYLONGPROFILE'", ParserError.INVALID_LENGTH_FOR_LITERAL);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"PROFILE 'PROF' DISP KEEP COPIES 5",
		"COPIES 3 PROFILE 'PROF' DISP DEL",
		"DISP HOLD COPES 2 DISP HOLD"
	})
	void parseADefinePrinterWithAnyOrderOfDispProfileAndCopies(String order)
	{
		assertParsesWithoutDiagnostics("DEFINE PRINTER (2) %s".formatted(order));
	}

	@Test
	void parseClosePrinterWithPrinterNumber()
	{
		var closePrinter = assertParsesSingleStatement("CLOSE PRINTER(5)", IClosePrinterNode.class);
		assertThat(closePrinter.printer().kind()).isEqualTo(SyntaxKind.NUMBER_LITERAL);
		assertThat(closePrinter.printer().intValue()).isEqualTo(5);
	}

	@Test
	void parseClosePrinterWithPrinterName()
	{
		var closePrinter = assertParsesSingleStatement("CLOSE PRINTER (PR5)", IClosePrinterNode.class);
		assertThat(closePrinter.printer().kind()).isEqualTo(SyntaxKind.IDENTIFIER);
		assertThat(closePrinter.printer().symbolName()).isEqualTo("PR5");
	}

	@Test
	void rudimentaryParseDefineWindow()
	{
		var window = assertParsesSingleStatement("DEFINE WINDOW MAIN", IDefineWindowNode.class);
		assertThat(window.name().symbolName()).isEqualTo("MAIN");
	}

	@Test
	void parseFormat()
	{
		var statementList = assertParsesWithoutDiagnostics("""
			FORMAT (PR15) AD=IO AL=5 CD=BL DF=S DL=29 EM=YYYY-MM-DD ES=ON FC= FL=2 GC=a HC=L HW=OFF IC= IP=ON IS=OFF LC=- LS=5 MC=3 MP=2 MS=ON NL=20 PC=3 PM=I PS=40 SF=3 SG=0 TC= UC=
			ZP=ON""");
		assertThat(statementList.statements().size()).isEqualTo(1);
	}

	@Test
	void parseFormatWithPrinterNumber()
	{
		var statementList = assertParsesWithoutDiagnostics("FORMAT (5) LS=5 ZP=ON");
		assertThat(statementList.statements().size()).isEqualTo(1);
	}

	@Test
	void parseFormatIfNextLineStartsWithStatement()
	{
		// If a format thingy is empty, the next line should still properly be identified as the next statement
		var statementList = assertParsesWithoutDiagnostics("""
			FORMAT (PR15) AD=IO AL=5 CD=BL DF=S DL=29 EM=YYYY-MM-DD ES=ON FC= FL=2 GC=a HC=L HW=OFF IC= IP=ON IS=OFF LC=- LS=5 MC=3 MP=2 MS=ON NL=20 PC=3 PM=I PS=40 SF=3 SG=0 TC= UC=
			ZP=
			DEFINE PRINTER (5)""");

		assertThat(statementList.statements().size()).isEqualTo(2);
		assertThat(statementList.statements().get(0)).isInstanceOf(IFormatNode.class);
		assertThat(statementList.statements().get(1)).isInstanceOf(IDefinePrinterNode.class);
	}

	@Test
	void parseWriteWithReportSpecification()
	{
		var write = assertParsesSingleStatement("WRITE (REP1)", IWriteNode.class);
		assertThat(write.reportSpecification()).map(SyntaxToken::source).hasValue("REP1");
	}

	@Test
	void parseWriteWithAttributeDefinition()
	{
		var write = assertParsesSingleStatement("WRITE (AD=UL AL=17 NL=8)", IWriteNode.class);
		assertThat(write.descendants()).hasSize(10);
	}

	@Test
	void parseWriteWithNoTitleAndNoHdr()
	{
		var write = assertParsesSingleStatement("WRITE NOTITLE NOHDR", IWriteNode.class);
		assertThat(write.findDescendantToken(SyntaxKind.NOTITLE)).isNotNull();
		assertThat(write.findDescendantToken(SyntaxKind.NOHDR)).isNotNull();
	}

	@Test
	void parseASimpleExamineReplace()
	{
		var examine = assertParsesSingleStatement("EXAMINE #VAR 'a' REPLACE 'b'", IExamineNode.class);
		assertThat(examine.examined()).isNotNull();
		assertThat(assertNodeType(examine.examined(), IVariableReferenceNode.class).referencingToken().symbolName()).isEqualTo("#VAR");
	}

	@Test
	void parseAnExamineWithSubstring()
	{
		var examine = assertParsesSingleStatement("EXAMINE SUBSTR(#VAR, 1, 5) FOR 'a'", IExamineNode.class);
		assertThat(examine.examined()).isNotNull();
		var substringOperand = assertNodeType(examine.examined(), ISubstringOperandNode.class);
		assertThat(assertNodeType(substringOperand.operand(), IVariableReferenceNode.class).referencingToken().symbolName()).isEqualTo("#VAR");
		assertThat(assertNodeType(substringOperand.startPosition(), ILiteralNode.class).token().intValue()).isEqualTo(1);
		assertThat(assertNodeType(substringOperand.length(), ILiteralNode.class).token().intValue()).isEqualTo(5);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"DELIMITER", "DELIMITERS",
		"DELIMITER ' '", "DELIMITERS ' '",
		"DELIMITER #DEL", "DELIMITERS #DEL",
	})
	void parseAnExamineWithDelimiters(String delimiter)
	{
		assertParsesSingleStatement("EXAMINE #VAR FOR #VAR2 WITH %s GIVING INDEX #INDEX".formatted(delimiter), IExamineNode.class);
	}

	@Test
	void parseAComplexExamineReplace()
	{
		var examine = assertParsesSingleStatement("EXAMINE FORWARD FULL VALUE OF #DOC STARTING FROM POSITION 7 ENDING AT POSITION 10 FOR FULL VALUE OF PATTERN #HTML(*) WITH DELIMITERS ',' AND REPLACE FIRST WITH FULL VALUE OF #TAB(*) ", IExamineNode.class);
		assertThat(examine.descendants().size()).isEqualTo(31);
	}

	@Test
	void parseAComplexExamineDelete()
	{
		var examine = assertParsesSingleStatement("EXAMINE FORWARD FULL VALUE OF #DOC STARTING FROM POSITION 7 ENDING AT POSITION 10 FOR FULL VALUE OF PATTERN #HTML(*) WITH DELIMITERS ',' AND DELETE FIRST", IExamineNode.class);
		assertThat(examine.descendants().size()).isEqualTo(26);
	}

	@Test
	void parseAComplexExamineDeleteGiving()
	{
		var examine = assertParsesSingleStatement("EXAMINE FORWARD FULL VALUE OF #DOC STARTING FROM POSITION 7 ENDING AT POSITION 10 FOR FULL VALUE OF PATTERN #HTML(*) WITH DELIMITERS ',' AND DELETE FIRST GIVING INDEX IN #ASD #EFG #HIJ", IExamineNode.class);
		assertThat(examine.descendants().size()).isEqualTo(32);
	}

	@Test
	void parseAExamineWithMultipleGivings()
	{
		var examine = assertParsesSingleStatement("EXAMINE #DOC FOR FULL VALUE OF 'a' GIVING NUMBER #NUM GIVING POSITION #POS GIVING LENGTH #LEN GIVING INDEX #INDEX", IExamineNode.class);
		assertThat(examine.descendants().size()).isEqualTo(19);
	}

	@Test
	void parseAnExamineTranslateStatement()
	{
		var examine = assertParsesSingleStatement("EXAMINE #ASD AND TRANSLATE INTO UPPER CASE", IExamineNode.class);
		assertThat(examine.descendants().size()).isEqualTo(7);
	}

	@Test
	void parseAnExamineTranslateUsingStatement()
	{
		var examine = assertParsesSingleStatement("EXAMINE #ASD AND TRANSLATE USING INVERTED #EFG", IExamineNode.class);
		assertThat(examine.descendants().size()).isEqualTo(7);
	}

	@Test
	void parseNewPage()
	{
		var newPage = assertParsesSingleStatement("NEWPAGE EVEN IF TOP OF PAGE WITH TITLE 'The Title'", INewPageNode.class);
		assertThat(newPage.descendants()).hasSize(9);
	}

	@Test
	void parseNewPageWithoutTitle()
	{
		var newPage = assertParsesSingleStatement("NEWPAGE WHEN LESS THAN 10 LINES LEFT", INewPageNode.class);
		assertThat(newPage.descendants()).hasSize(7);
	}

	@Test
	void parseNewPageWithNumericReportSpecification()
	{
		var newPage = assertParsesSingleStatement("NEWPAGE(5) WHEN LESS 10 TITLE 'The Title'", INewPageNode.class);
		assertThat(newPage.reportSpecification()).map(SyntaxToken::intValue).hasValue(5);
		assertThat(newPage.descendants()).hasSize(9);
	}

	@Test
	void parseNewPageWithLogicalNameInReportSpecification()
	{
		var newPage = assertParsesSingleStatement("NEWPAGE(THEPRINT) IF LESS THAN #VAR LINES LEFT", INewPageNode.class);
		assertThat(newPage.reportSpecification()).map(SyntaxToken::symbolName).hasValue("THEPRINT");
		assertThat(newPage.descendants()).hasSize(10);
	}

	@Test
	void parseAtEndOfPage()
	{
		var endOfPage = assertParsesSingleStatement("""
			AT END OF PAGE (PRNT)
			IGNORE
			END-ENDPAGE
			""", IEndOfPageNode.class);

		assertThat(endOfPage.reportSpecification()).map(SyntaxToken::symbolName).hasValue("PRNT");
		assertThat(endOfPage.body().statements()).hasSize(1);
	}

	@Test
	void parseEndPage()
	{
		var endOfPage = assertParsesSingleStatement("""
			END PAGE (5)
			IGNORE
			END-ENDPAGE
			""", IEndOfPageNode.class);

		assertThat(endOfPage.reportSpecification()).map(SyntaxToken::intValue).hasValue(5);
		assertThat(endOfPage.body().statements()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"AT END OF PAGE",
		"END PAGE",
		"END OF PAGE",
		"AT END PAGE"
	})
	void parseMultipleHeaderOptionsForEndOfPage(String header)
	{
		assertParsesWithoutDiagnostics("""
				%s
				IGNORE
				END-ENDPAGE
			""".formatted(header));
	}

	@Test
	void parseAtTopOfPage()
	{
		var topOfPage = assertParsesSingleStatement("""
			AT TOP OF PAGE (PRNT)
			IGNORE
			END-TOPPAGE
			""", ITopOfPageNode.class);

		assertThat(topOfPage.reportSpecification()).map(SyntaxToken::symbolName).hasValue("PRNT");
		assertThat(topOfPage.body().statements()).hasSize(1);
	}

	@Test
	void parseTopPage()
	{
		var topOfPage = assertParsesSingleStatement("""
			TOP PAGE (5)
			IGNORE
			END-TOPPAGE
			""", ITopOfPageNode.class);

		assertThat(topOfPage.reportSpecification()).map(SyntaxToken::intValue).hasValue(5);
		assertThat(topOfPage.body().statements()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"AT TOP OF PAGE",
		"TOP PAGE",
		"TOP OF PAGE",
		"AT TOP PAGE"
	})
	void parseMultipleHeaderOptionsForTopOfPage(String header)
	{
		assertParsesWithoutDiagnostics("""
			%s
			IGNORE
			END-TOPPAGE
			""".formatted(header));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"AT START OF DATA (R1.)",
		"START DATA",
		"START OF DATA",
		"AT START DATA (R5.)"
	})
	void parseAtStartOfData(String header)
	{
		var startOfData = assertParsesSingleStatement("""
			%s
			IGNORE
			END-START
			""".formatted(header), IStartOfDataNode.class);

		assertThat(startOfData.body().statements()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"AT END OF DATA (R1.)",
		"END DATA",
		"END OF DATA",
		"AT END DATA (R5.)"
	})
	void parseAtEndOfData(String header)
	{
		var endOfData = assertParsesSingleStatement("""
			%s
			IGNORE
			END-ENDDATA
			""".formatted(header), IEndOfDataNode.class);

		assertThat(endOfData.body().statements()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"AT BREAK (RD.) OF #VAR /5/",
		"BREAK #VARIABLE",
		"AT BREAK #VAR",
		"BREAK (R1.) OF #VAR /10/"
	})
	void parseAtBreakOf(String header)
	{
		var breakOf = assertParsesSingleStatement("""
			%s
			IGNORE
			END-BREAK
			""".formatted(header), IBreakOfNode.class);

		assertThat(breakOf.body().statements()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"EJECT ON (0)",
		"EJECT OFF (PRNT)",
		"EJECT OFF",
		"EJECT ON",
		"EJECT (PRNT)",
		"EJECT (5)",
		"EJECT",
		"EJECT IF LESS THAN 10 LINES LEFT",
		"EJECT WHEN LESS THAN #VAR LINES LEFT",
		"EJECT (10) LESS #VAR",
		"EJECT (10) WHEN LESS #VAR",
		"EJECT (10) WHEN LESS THAN #VAR",
		"EJECT (10) WHEN LESS THAN #VAR LEFT",
		"EJECT (PRNT) IF LESS THAN 10 LINES LEFT",
	})
	void parseEject(String eject)
	{
		var statements = assertParsesWithoutDiagnostics(eject);
		assertThat(statements.statements()).hasSize(1);
		assertThat(statements.statements().get(0)).isInstanceOf(IEjectNode.class);
	}

	@Test
	void parseEjectWithPrinterReference()
	{
		var eject = assertParsesSingleStatement("EJECT (PRNT)", IEjectNode.class);
		assertThat(eject.reportSpecification()).map(SyntaxToken::symbolName).hasValue("PRNT");
	}

	@Test
	void parseEjectWithNumericPrinterReference()
	{
		var eject = assertParsesSingleStatement("EJECT (5)", IEjectNode.class);
		assertThat(eject.reportSpecification()).map(SyntaxToken::intValue).hasValue(5);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"ESCAPE TOP REPOSITION",
		"ESCAPE TOP",
		"ESCAPE BOTTOM IMMEDIATE",
		"ESCAPE BOTTOM (RD.) IMMEDIATE",
		"ESCAPE BOTTOM",
		"ESCAPE BOTTOM (R1.)",
		"ESCAPE ROUTINE IMMEDIATE",
		"ESCAPE ROUTINE",
		"ESCAPE MODULE IMMEDIATE",
		"ESCAPE MODULE"
	})
	void parseEscapes(String escape)
	{
		assertParsesSingleStatement(escape, IEscapeNode.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"TOP", "BOTTOM", "ROUTINE", "MODULE"
	})
	void parseEscapeDirectionOfEscapeNode(String direction)
	{
		var escape = assertParsesSingleStatement("ESCAPE %s".formatted(direction), IEscapeNode.class);
		assertThat(escape.escapeDirection().name()).isEqualTo(direction);
	}

	@Test
	void parseEscapeImmediate()
	{
		var escape = assertParsesSingleStatement("ESCAPE ROUTINE IMMEDIATE", IEscapeNode.class);
		assertThat(escape.isImmediate()).isTrue();
	}

	@Test
	void parseEscapeReposition()
	{
		var escape = assertParsesSingleStatement("ESCAPE TOP REPOSITION", IEscapeNode.class);
		assertThat(escape.isReposition()).isTrue();
	}

	@Test
	void parseEscapeLabel()
	{
		var escape = assertParsesSingleStatement("ESCAPE BOTTOM (RD.)", IEscapeNode.class);
		assertThat(escape.label()).map(SyntaxToken::symbolName).hasValue("RD.");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"STACK TOP COMMAND 'ASD' #VAR 'ASDF'",
		"STACK 'MOD'",
		"STACK DATA FORMATTED #DATA1 #DATA2 #DATA3",
		"STACK TOP DATA #VAR1 #VAR2",
		"STACK TOP FORMATTED #VAR1 #VAR2"
	})
	void parseStack(String stack)
	{
		var statementList = assertParsesWithoutDiagnostics(stack);
		assertThat(statementList.statements()).hasSize(1);
		assertThat(statementList.statements().get(0)).isInstanceOf(IStackNode.class);
	}

	@Test
	void parseStackWithManyOperands()
	{
		var statementList = assertParsesWithoutDiagnostics("""
			STACK TOP COMMAND #ASD #ASDF 'ASD' #ASDFG
			IGNORE
			""");
		assertThat(statementList.statements()).hasSize(2);
		assertThat(statementList.statements().get(0)).isInstanceOf(IStackNode.class);
		assertThat(statementList.statements().get(1)).isInstanceOf(IIgnoreNode.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"BEFORE BREAK PROCESSING",
		"BEFORE BREAK",
		"BEFORE",
		"BEFORE PROCESSING"
	})
	void parseBeforeBreakProcessing(String header)
	{
		var beforeBreak = assertParsesSingleStatement("""
			%s
			IGNORE
			END-BEFORE
			""".formatted(header), IBeforeBreakNode.class);

		assertThat(beforeBreak.body().statements()).hasSize(1);
		assertThat(beforeBreak.body().statements().first()).isInstanceOf(IIgnoreNode.class);
	}

	@Test
	void parseASimpleHistogram()
	{
		var histogram = assertParsesSingleStatement("""
			HISTOGRAM THE-VIEW THE-DESC STARTING FROM 'M'
			IGNORE
			END-HISTOGRAM""", IHistogramNode.class);
		assertThat(histogram.view().token().symbolName()).isEqualTo("THE-VIEW");
		assertThat(histogram.descriptor().symbolName()).isEqualTo("THE-DESC");
	}

	@Test
	void parseAHistogramWithNumber()
	{
		assertParsesSingleStatement("""
			HISTOGRAM(1) THE-VIEW THE-DESC
			IGNORE
			END-HISTOGRAM""", IHistogramNode.class);
	}

	@Test
	void parseHistogramWithAll()
	{
		assertParsesSingleStatement("""
			HISTOGRAM ALL THE-VIEW THE-DESC
			IGNORE
			END-HISTOGRAM""", IHistogramNode.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"ON", "OFF"
	})
	void parseHistogramWithMultiFetch(String multifetch)
	{
		assertParsesSingleStatement("""
			HISTOGRAM ALL MULTI-FETCH %s THE-VIEW THE-DESC
			IGNORE
			END-HISTOGRAM""".formatted(multifetch), IHistogramNode.class);
	}

	@Test
	void parseHistogramWithStartingFrom()
	{
		assertParsesSingleStatement("""
			HISTOGRAM IN FILE THE-VIEW VALUE FOR FIELD THE-DESC STARTING FROM VALUES #ABC ENDING AT #DEF
			IGNORE
			END-HISTOGRAM""", IHistogramNode.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"IN ASCENDING SEQUENCE",
		"IN DESCENDING SEQUENCE",
		"IN ASC",
		"IN DESC",
		"ASC",
		"DESC",
		"IN VARIABLE #VAR2",
		"DYNAMIC #VAR2"
	})
	void parseHistogramWithSorting(String sorting)
	{
		assertParsesSingleStatement("""
			HISTOGRAM THE-VIEW %s THE-DESC
			IGNORE
			END-HISTOGRAM""".formatted(sorting), IHistogramNode.class);
	}

	private <T extends IStatementNode> T assertParsesSingleStatement(String source, Class<T> nodeType)
	{
		var result = super.assertParsesWithoutDiagnostics(source);
		return assertNodeType(result.statements().first(), nodeType);
	}
}
