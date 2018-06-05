#!/usr/bin/python

"""
Utility for creating a Jasmine spec for testing the JavaScript autosuggest module, out of
JUnit tests used for testing the equivalent Java module.

Since the antlr4 runtime for JavaScript requires generated source code for each grammar,
this utility extract tests grammars used in the Java unit test class, and generates
JavaScript code for them. Lexers are added code for exposing their internal ATN (needed
until pull request #2141 is accepted by the antlr4 project).

You need to have antlr4 in the path for this utility to work.
The result will be a complete Jasmine test file at TESTS_OUT_FILENAME, together with a directory
of generated test grammar parsers/lexers/listeners at GENERATED_JS_CODE_OUT_DIR.
"""
import os
import re
import tempfile
import shutil
import glob
import subprocess

JAVA_TEST_FILE = 'src/test/java/com/intigua/antlr4/autosuggest/AutoSuggesterTest.java'
TESTS_OUT_FILENAME = 'generatedTestsFromJava.spec.js'
GENERATED_JS_CODE_OUT_DIR = 'testGrammars'

ATN_PATCH_TEMPLATE = r"""Object.defineProperty(%(lexer_name)s.prototype, "atn", {
	get : function() {
		return atn;
	}
});

"""

JS_CASE_TEMPLATE = r"""    it('should handle grammar "%(embeddable_grammar)s" with input "%(input_text)s"', function () {
        givenGrammar(%(grammar_name)sLexer.%(grammar_name)sLexer, %(grammar_name)sParser.%(grammar_name)sParser);
%(case_preference_statement)s        whenInput('%(input_text)s');
        thenExpect([%(output)s]);
    });
"""

JS_FILE_TEMPLATE = r'''%(header_comment)s
const autosuggest = require('../autosuggest');
%(grammar_reqs)s

describe('Autosuggest', function () {
    let completions;
    let storedLexerCtr;
    let storedParserCtr;
    let storedCasePreference;

    const givenGrammar = function (lexerCtr, parserCtr) {
        storedLexerCtr = lexerCtr;
        storedParserCtr = parserCtr;
    };
    const withCasePreference = function(casePreference) {
        storedCasePreference = casePreference;
    };
    const whenInput = function (input) {
        let suggester = autosuggest.autosuggester(storedLexerCtr, storedParserCtr, storedCasePreference);
        completions = suggester.autosuggest(input);
    };
    const thenExpect = function (expectedSuggestions) {
        expect(completions.sort()).toEqual(expectedSuggestions.sort());
    };

%(test_cases)s
});
'''

COMMENT_RE = r'//.*'
def line_is_relevant(line):
    line = re.sub(COMMENT_RE, '', line)
    return "givenGrammar" in line and "whenInput" in line and '.class' not in line


def sort_uniq(seq):
    seen = set()
    seen_add = seen.add
    return [x for x in seq if not (x in seen or seen_add(x))]


def to_name(grammar):
    gname = re.sub(r'\s', '', grammar)
    gname = gname[:-1]
    gname = re.sub(r'\'', '_Q_', gname)
    gname = re.sub(r'\+', '_PLUS_', gname)
    gname = re.sub(r'\*', '_STAR_', gname)
    gname = re.sub(r'\?', '_QUES_', gname)
    gname = re.sub(r'\(', '_LPAR_', gname)
    gname = re.sub(r'\)', '_RPAR_', gname)
    gname = re.sub(r'->', '_ARRW_', gname)
    gname = re.sub(r'\\', '_BS_', gname)
    gname = re.sub(r'\W', '_', gname)
    return gname

LINE_RE = ''.join([
    r'givenGrammar\("(?P<grammar>.*)"\)',
    r'(?:\.withCasePreference\((?P<casepref>.*)\))?',
    r'\.whenInput\("(?P<input_text>.*)"\)',
    r'\.thenExpect\((?P<output>.*)\);'
])

def process(line):
    line = line.strip()
    m = re.search(LINE_RE, line)
    if not m:
        raise BaseException("No match for relevant line: " + line)
    grammar = m.group('grammar').replace('\\\\', '\\')
    input_text = m.group('input_text')
    output = m.group('output')
    grammar = re.sub('", *"', '; ', grammar) + ";\n"
    case_preference_stmt = ''
    if 'casepref' in m.groupdict() and m.group('casepref') is not None:
        case_preference_value = m.group('casepref')
        if case_preference_value != 'null':
            case_preference_value = "'" + case_preference_value + "'"
        case_preference_stmt = "        withCasePreference(" + case_preference_value + ");\n"
    return {
        "grammar_name": to_name(grammar),
        "grammar": grammar,
        "embeddable_grammar": grammar.strip().replace("\\", "\\\\").replace("'", "\\'"),
        "case_preference_statement": case_preference_stmt,
        "input_text": input_text,
        "output": output
    }


def generate_grammar(output_dir, grammar):
    gname = to_name(grammar)
    filename = os.path.join(output_dir, gname + ".g4")
    if os.path.exists(filename):
        return
    full = "grammar %s;\n%s" % (gname, grammar)
    with open(filename, "w") as text_file:
        text_file.write("{0}".format(full))
    subprocess.check_call(["antlr4", "-Dlanguage=JavaScript", filename])

def post_process(filename):
    with open(filename, "r") as fh:
        lines = fh.readlines()
    lines.pop(0)
    with open(filename, "w") as fh:
        for line in lines:
            fh.write(line)

def collect_generated_code(tdir):
    if os.path.exists(GENERATED_JS_CODE_OUT_DIR):
        shutil.rmtree(GENERATED_JS_CODE_OUT_DIR)
    os.mkdir(GENERATED_JS_CODE_OUT_DIR)
    files_to_collect = glob.glob(os.path.join(tdir, '*er.js'))
    for file_to_collect in files_to_collect:
        post_process(file_to_collect)
        shutil.move(file_to_collect, GENERATED_JS_CODE_OUT_DIR)

def patch_lexer_file_for_atn(lexer_file):
    lexer_name = os.path.splitext(lexer_file)[0]
    with open(os.path.join(GENERATED_JS_CODE_OUT_DIR, lexer_file), "a") as f:
        atn_patch = ATN_PATCH_TEMPLATE % {"lexer_name": lexer_name}
        f.write(atn_patch)

def generate_grammars(unique_grammars):
    tdir = tempfile.mkdtemp()
    for grammar in unique_grammars:
        generate_grammar(tdir, grammar)
    collect_generated_code(tdir)
    lexer_files = [os.path.basename(f) for f in glob.glob(GENERATED_JS_CODE_OUT_DIR+"/*Lexer.js")]
    for f in lexer_files:
        patch_lexer_file_for_atn(f)
#    shutil.rmtree(tdir)

REQ_TEMPLATE = """const %(grammar_name)sLexer = require('./testGrammars/%(grammar_name)sLexer');
const %(grammar_name)sParser = require('./testGrammars/%(grammar_name)sParser');
"""
def to_parser_req(grammar):
    return REQ_TEMPLATE % {"grammar_name": to_name(grammar)}

def generate_test_code(test_cases, unique_grammar_names):
    test_cases_code = "\n".join([JS_CASE_TEMPLATE % test_case for test_case in test_cases])
    grammar_reqs = "".join([to_parser_req(tc) for tc in unique_grammar_names])
    header_comment = "// Generated by " + os.path.basename(__file__) + " in antlr4-autosuggest project."
    test_code = JS_FILE_TEMPLATE % {
        "test_cases": test_cases_code,
        "grammar_reqs": grammar_reqs,
        "header_comment": header_comment
    }
    with open(TESTS_OUT_FILENAME, 'w') as outfile:
        outfile.write(test_code)

def main():
    with open(JAVA_TEST_FILE) as f:
        content = f.readlines()
    content = [process(l) for l in content if line_is_relevant(l)]
    all_grammars = [c["grammar"] for c in content]
    unique_grammar_names = list(sort_uniq(all_grammars))
    generate_grammars(unique_grammar_names)
    generate_test_code(content, unique_grammar_names)
main()
