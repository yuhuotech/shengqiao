"""Inspect Xiaomi's recorder-only metadata backup without printing private content."""
import argparse
import collections
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zlib

parser = argparse.ArgumentParser()
parser.add_argument("backup", type=Path)
args = parser.parse_args()
raw = args.backup.read_bytes()
android_header = raw.index(b"ANDROID BACKUP\n")
stream = io.BytesIO(raw[android_header:])
magic, version, compressed, encryption = [stream.readline().strip() for _ in range(4)]
if encryption != b"none":
    raise SystemExit("Encrypted backup: no inspection performed")
payload = stream.read()
if compressed == b"1":
    payload = zlib.decompress(payload)
with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
    members = archive.getmembers()
    targets = [member for member in members if member.name.endswith("/backup/backup.json")]
    if len(targets) != 1:
        raise SystemExit("Expected exactly one recorder backup.json")
    metadata = json.load(archive.extractfile(targets[0]))

records = metadata.get("mRecordFileList", [])
sentences = metadata.get("sentenceList", [])
record_ids = {record.get("sha1") for record in records}
sentence_ids = {sentence.get("recordSha1") for sentence in sentences}
local = [record for record in records if record.get("inLocal")]
stats = {
    "backup_bytes": len(raw),
    "backup_sha256": hashlib.sha256(raw).hexdigest(),
    "json_bytes": targets[0].size,
    "archive_member_count": len(members),
    "audio_files_in_backup": sum(member.name.lower().endswith((".mp3", ".m4a", ".wav"))
                                  for member in members),
    "record_count": len(records),
    "record_unique_sha1_count": len(record_ids),
    "local_record_count": len(local),
    "deleted_record_count": len(metadata.get("mDeleteLocalRecordList", [])),
    "sentence_count": len(sentences),
    "nonempty_sentence_count": sum(bool(s.get("lyric")) for s in sentences),
    "sentence_plain_text_characters": sum(len(s.get("content", "")) for s in sentences),
    "sentence_lyric_serialized_characters": sum(len(s.get("lyric", "")) for s in sentences),
    "transcribed_record_count": len(sentence_ids),
    "transcribed_records_matched_to_audio_metadata": len(sentence_ids & record_ids),
    "unmatched_transcribed_record_count": len(sentence_ids - record_ids),
    "summary_metadata_count": len(metadata.get("mSumaryList", [])),
    "summary_nonempty_body_count": sum(bool(s.get("summaryResult"))
                                       for s in metadata.get("mSumaryList", [])),
    "local_audio_extensions": dict(collections.Counter(Path(r.get("filePath", "")).suffix
                                                        for r in local)),
}
longest = max(local, key=lambda record: record.get("duration", 0), default={})
long_sentences = [s for s in sentences if s.get("recordSha1") == longest.get("sha1")]
stats["longest_local_record"] = {
    "duration_raw": longest.get("duration"),
    "sentence_count": len(long_sentences),
    "plain_text_characters": sum(len(s.get("content", "")) for s in long_sentences),
    "timeline_type": type(long_sentences[0].get("timeLine")).__name__ if long_sentences else None,
}
print(json.dumps(stats, ensure_ascii=False, indent=2))
