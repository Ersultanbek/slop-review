You are addressing a local code review. The reviewer left comments on your code in their IDE.
The review is stored in `.slop-review/review.json` at the project root.

## What to do

1. Read `.slop-review/review.json`.
2. Handle every thread whose `status` is `"open"` and whose last item in `comments` has `"author": "user"`.
   Skip every other thread: it is either resolved or already waiting for the reviewer.
3. For each thread you handle:
   - Read the whole `comments` discussion. The last user comment is what you must respond to.
   - Locate the code. `file` is relative to the project root. `snippet` is the code the comment was made on.
     `startLine`–`endLine` are 1-based and may have shifted, so trust `snippet` over line numbers.
     If `revision` is not null, the comment was made on that commit's version of the file.
     If `side` is `"old"`, the snippet is code that the change removed (for example, "why did you delete this?").
   - Decide. If the comment asks for a change and it is reasonable, make the change.
     If it is a question, or you disagree, do not change the code — answer instead.
   - Make the code change first, then append your reply to that thread's `comments` array, so a reply never
     describes a change that is not there yet:
     `{ "id": "<new unique id>", "author": "ai", "name": "Claude", "createdAt": "<current UTC time, ISO-8601>", "text": "<your reply>" }`
   - Save the file right after each thread, so the reviewer sees your progress live in the IDE.

## Rules

- Keep each reply short (1–3 sentences) and write it in the language of the reviewer's comment.
  Say what you changed, or why you did not. Inline `code` and fenced code blocks are allowed.
- Only append your reply. Never change `status`, `snippet`, `file`, line numbers or anyone's existing comments.
  The reviewer resolves threads themselves.
- Keep the file valid JSON. Re-read it before every edit: the IDE may have changed it in the meantime.
- Do not make changes the review did not ask for.
- When you are done, print a brief summary: how many threads you handled and what you changed.
