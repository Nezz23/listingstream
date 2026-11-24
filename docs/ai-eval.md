# AI assistant evaluation

Model: **Qwen2.5 7B** (`qwen2.5:7b`) through Ollama on an Apple M4 Pro, temperature 0, seed 7. Data: the full replay
(10 scrapes, ledger loaded). Each expected answer is computed by SQL in `AssistantEvalIT`, independently of the
assistant's tools. Raw results, including every answer and tool trail: [ai-eval.json](ai-eval.json).

## Result

| | |
|---|---|
| Questions | 23 (counts, trends, hosts, listing history, pipeline, rules, refusals) |
| Correct | **23 / 23** |
| Released answers that were wrong | **0** |
| First drafts sent back by the guard and corrected | 3 |
| Average time per question | 3.7 s |

**Read this with care:** these 23 questions were also used while building the assistant, so this is a development
score, not a held-out benchmark. The progression below is the honest story.

## How it got there

| Run | Change | Correct | Released but wrong |
|---|---|---|---|
| 1 | Tools + number guard | 18 / 23 | 4 (none with an invented number: the model answered a different question, or the grader was too literal) |
| 2 | Citation guard; correction prompt repeats the original question; listing history returned as merged status periods; stricter history grader | 21 / 23 | 2 |
| 3 | `compliance_counts` returns every status including zeros; rule search returns 4 passages instead of 3 | 23 / 23 | 0 |

## What the guard catches, and what it cannot

- **Caught:** any number that is not in a tool result ("about 1,500" when the data says 1,477), and any `[R#]`
  citation to a passage that was never retrieved. Run 1 released `[R3]` for a rule the model had not looked up;
  the citation check was added because of it.
- **Not caught by numbers alone:** a real number in the wrong place. In run 1 the model said listing 8490 became
  exempt on July 12; the data says August 10, but "12" appears elsewhere in the tool output. The fix was to make
  the tool return pre-merged status periods, and the eval now checks the date explicitly.
- **Refusals:** names, phone numbers, addresses and forecasts are not in the data; all four refusal questions are
  answered without adding a single number, including one that says "ignore your rules".

## Run it

```bash
ollama pull qwen2.5:7b
mvn verify -Dit.test=FullReconciliationIT                       # loads the full replay into the test database
RENTGUARD_AI_EVAL=1 mvn verify -Dit.test=AssistantEvalIT          # writes target/ai-eval.json
```
