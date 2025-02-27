package org.amshove.natlint.analyzers;

import org.amshove.natlint.linter.AbstractAnalyzerTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HiddenWorkfileAnalyzerShould extends AbstractAnalyzerTest
{
	protected HiddenWorkfileAnalyzerShould()
	{
		super(new HiddenWorkfileAnalyzer());
	}

	@ParameterizedTest
	@CsvSource(
		{
			"C,DEFINE WORK FILE 1",
			"C,READ WORK FILE 1 #RECORD;END-WORK",
			"C,WRITE WORK FILE 1 #RECORD",
			"C,CLOSE WORK FILE 1 TRANSACTION",
			"H,DEFINE WORK FILE 1",
			"H,READ WORK FILE 1 #RECORD;END-WORK",
			"H,WRITE WORK FILE 1 #RECORD",
			"H,CLOSE WORK FILE 1",
			"M,DEFINE WORK FILE 1",
			"M,READ WORK FILE 1 #RECORD;END-WORK",
			"M,WRITE WORK FILE 1 #RECORD",
			"M,CLOSE WORK FILE 1",
			"N,DEFINE WORK FILE 1",
			"N,READ WORK FILE 1 ONCE #RECORD",
			"N,WRITE WORK FILE 1 #RECORD",
			"N,CLOSE WORK FILE 1",
			"S,DEFINE WORK FILE 1",
			"S,READ WORK FILE 1 #RECORD;END-WORK",
			"S,WRITE WORK FILE 1 #RECORD",
			"S,CLOSE WORK FILE 1"
		}
	)
	void raiseADiagnosticForETForOtherObjectTypesThanProgram(String objtype, String statement)
	{
		configureEditorConfig("""
			[*]
			natls.style.discourage_hiddenworkfiles=true
			""");

		testDiagnostics(
			"OBJECT.NS%s".formatted(objtype), """
            %s
            END
			""".formatted(statement),
			expectDiagnostic(0, HiddenWorkfileAnalyzer.HIDDEN_WORKFILE_STATEMENT_IS_DISCOURAGED)
		);
	}

	@ParameterizedTest
	@ValueSource(strings =
	{
		"DEFINE WORK FILE 1",
		"READ WORK FILE 1 #RECORD;END-WORK",
		"READ WORK FILE 1 ONCE #RECORD",
		"WRITE WORK FILE 1 #RECORD",
		"CLOSE WORK FILE 1",
		"STOP"
	})
	void notRaiseADiagnosticForETInProgram(String statement)
	{
		configureEditorConfig("""
			[*]
			natls.style.discourage_hiddenworkfiles=true
			""");

		testDiagnostics(
			"OBJECT.NSP", """
            %s
            END
			""".formatted(statement),
			expectNoDiagnostic(0, HiddenWorkfileAnalyzer.HIDDEN_WORKFILE_STATEMENT_IS_DISCOURAGED)
		);
	}

	@Test
	void raiseNoDiagnosticIfOptionIsFalse()
	{
		configureEditorConfig("""
			[*]
			natls.style.discourage_hiddenworkfiles=false
			""");

		testDiagnostics(
			"OBJECT.NSN", """
			CLOSE WORK FILE 1
			END
			""",
			expectNoDiagnosticOfType(HiddenWorkfileAnalyzer.HIDDEN_WORKFILE_STATEMENT_IS_DISCOURAGED)
		);
	}
}
