using System;
using System.Collections.Generic;
using System.Text;

namespace Mca.Benchmark
{
    public static class StrictJsonValidator
    {
        public static void Validate(string json)
        {
            new Parser(json).ParseDocument();
        }

        private sealed class Parser
        {
            private readonly string text;
            private int index;

            internal Parser(string text)
            {
                if (text == null) throw new ArgumentNullException("json");
                this.text = text;
            }

            internal void ParseDocument()
            {
                SkipWhitespace();
                ParseValue();
                SkipWhitespace();
                if (index != text.Length) Fail("Unexpected trailing content");
            }

            private void ParseValue()
            {
                if (index >= text.Length) Fail("Unexpected end of JSON");
                char c = text[index];
                if (c == '{') ParseObject();
                else if (c == '[') ParseArray();
                else if (c == '"') ReadString();
                else if (c == 't') ReadLiteral("true");
                else if (c == 'f') ReadLiteral("false");
                else if (c == 'n') ReadLiteral("null");
                else if (c == '-' || (c >= '0' && c <= '9')) ParseNumber();
                else Fail("Unexpected character '" + c + "'");
            }

            private void ParseObject()
            {
                index++;
                SkipWhitespace();
                var names = new HashSet<string>(StringComparer.Ordinal);
                if (Take('}')) return;
                while (true)
                {
                    if (index >= text.Length || text[index] != '"') Fail("Expected an object property name");
                    string name = ReadString();
                    if (!names.Add(name)) Fail("Duplicate object property '" + name + "'");
                    SkipWhitespace();
                    Require(':');
                    SkipWhitespace();
                    ParseValue();
                    SkipWhitespace();
                    if (Take('}')) return;
                    Require(',');
                    SkipWhitespace();
                }
            }

            private void ParseArray()
            {
                index++;
                SkipWhitespace();
                if (Take(']')) return;
                while (true)
                {
                    ParseValue();
                    SkipWhitespace();
                    if (Take(']')) return;
                    Require(',');
                    SkipWhitespace();
                }
            }

            private string ReadString()
            {
                Require('"');
                var builder = new StringBuilder();
                while (index < text.Length)
                {
                    char c = text[index++];
                    if (c == '"') return builder.ToString();
                    if (c < 0x20) Fail("Unescaped control character in string");
                    if (c == '\\')
                    {
                        if (index >= text.Length) Fail("Incomplete string escape");
                        char escaped = text[index++];
                        switch (escaped)
                        {
                            case '"': builder.Append('"'); break;
                            case '\\': builder.Append('\\'); break;
                            case '/': builder.Append('/'); break;
                            case 'b': builder.Append('\b'); break;
                            case 'f': builder.Append('\f'); break;
                            case 'n': builder.Append('\n'); break;
                            case 'r': builder.Append('\r'); break;
                            case 't': builder.Append('\t'); break;
                            case 'u':
                                char first = (char)ReadHex4();
                                if (char.IsHighSurrogate(first))
                                {
                                    if (index + 5 >= text.Length || text[index] != '\\' || text[index + 1] != 'u')
                                        Fail("High surrogate is not followed by a low surrogate");
                                    index += 2;
                                    char second = (char)ReadHex4();
                                    if (!char.IsLowSurrogate(second)) Fail("Invalid low surrogate");
                                    builder.Append(first).Append(second);
                                }
                                else
                                {
                                    if (char.IsLowSurrogate(first)) Fail("Unexpected low surrogate");
                                    builder.Append(first);
                                }
                                break;
                            default: Fail("Invalid string escape"); break;
                        }
                    }
                    else if (char.IsHighSurrogate(c))
                    {
                        if (index >= text.Length || !char.IsLowSurrogate(text[index])) Fail("Unpaired high surrogate");
                        builder.Append(c).Append(text[index++]);
                    }
                    else
                    {
                        if (char.IsLowSurrogate(c)) Fail("Unpaired low surrogate");
                        builder.Append(c);
                    }
                }
                Fail("Unterminated string");
                return null;
            }

            private int ReadHex4()
            {
                if (index + 4 > text.Length) Fail("Incomplete unicode escape");
                int value = 0;
                for (int i = 0; i < 4; i++)
                {
                    char c = text[index++];
                    int digit = c >= '0' && c <= '9' ? c - '0' :
                        c >= 'a' && c <= 'f' ? c - 'a' + 10 :
                        c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                    if (digit < 0) Fail("Invalid unicode escape");
                    value = (value << 4) | digit;
                }
                return value;
            }

            private void ParseNumber()
            {
                Take('-');
                if (Take('0'))
                {
                    if (index < text.Length && IsAsciiDigit(text[index])) Fail("Leading zero in number");
                }
                else
                {
                    RequireDigit();
                    while (index < text.Length && IsAsciiDigit(text[index])) index++;
                }
                if (Take('.'))
                {
                    RequireDigit();
                    while (index < text.Length && IsAsciiDigit(text[index])) index++;
                }
                if (index < text.Length && (text[index] == 'e' || text[index] == 'E'))
                {
                    index++;
                    if (index < text.Length && (text[index] == '+' || text[index] == '-')) index++;
                    RequireDigit();
                    while (index < text.Length && IsAsciiDigit(text[index])) index++;
                }
            }

            private void RequireDigit()
            {
                if (index >= text.Length || !IsAsciiDigit(text[index])) Fail("Expected a digit");
                index++;
            }

            private static bool IsAsciiDigit(char value)
            {
                return value >= '0' && value <= '9';
            }

            private void ReadLiteral(string literal)
            {
                if (index + literal.Length > text.Length ||
                    string.CompareOrdinal(text, index, literal, 0, literal.Length) != 0)
                    Fail("Invalid literal");
                index += literal.Length;
            }

            private void SkipWhitespace()
            {
                while (index < text.Length)
                {
                    char c = text[index];
                    if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
                    index++;
                }
            }

            private bool Take(char expected)
            {
                if (index < text.Length && text[index] == expected) { index++; return true; }
                return false;
            }

            private void Require(char expected)
            {
                if (!Take(expected)) Fail("Expected '" + expected + "'");
            }

            private void Fail(string message)
            {
                throw new FormatException(message + " at character " + index + ".");
            }
        }
    }
}
