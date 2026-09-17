"""Normalize the 300-case Skill routing dataset to the public Skill contracts."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / "backend/src/test/resources/eval/agent-skill-300.jsonl"

NO_PROFILE = "问题只需要论文中的局部事实或原文，不需要先建立全文画像。"
NEEDS_EVIDENCE = "回答依赖当前论文中的事实、方法、公式或结果，需要读取论文证据。"
NO_EVIDENCE = "问题不要求核对当前论文事实，也不需要论文证据。"
NO_ACTION = "用户没有要求修改、标注或跳转论文页面。"


def set_rationale(row: dict, profile: str, evidence: str, action: str) -> None:
    row["rationale"] = {
        "paper-profile": profile,
        "paper-evidence": evidence,
        "paper-action": action,
    }


def normalize(row: dict) -> dict:
    number = row["caseNumber"]
    topic = row["topicTitle"]
    hint = row.get("evidenceHint", "").strip()

    if number == 1:
        row["goldSkills"] = ["paper-profile", "paper-evidence"]
        set_rationale(
            row,
            "问题要求从全文组织研究问题、方法、结果和局限，当前没有可复用画像。",
            NEEDS_EVIDENCE,
            NO_ACTION,
        )
    elif number == 2:
        row["question"] = f"论文的实验或比较结果是什么？请针对“{hint}”列出比较对象、指标、条件和主要结论，并给出论文依据。"
        row["goldSkills"] = ["paper-evidence"]
        set_rationale(row, NO_PROFILE, NEEDS_EVIDENCE, NO_ACTION)
    elif number == 3:
        row["question"] = f"论文的核心创新点是什么？请围绕“{hint}”说明整体方法，以及各组成部分分别解决的问题，并给出论文依据。"
        row["goldSkills"] = ["paper-evidence"]
        set_rationale(
            row,
            "问题锁定明确的机制、组件或方法贡献，原文证据可以直接完成回答；画像可以辅助导航但不是必需。",
            NEEDS_EVIDENCE,
            NO_ACTION,
        )
    elif number == 4:
        row["question"] = f"论文对“{hint}”的实验或比较结果是什么？请列出指标、比较条件和主要结论，并给出论文依据。"
        row["goldSkills"] = ["paper-evidence"]
        set_rationale(row, NO_PROFILE, NEEDS_EVIDENCE, NO_ACTION)
    elif number == 5:
        row["question"] = f"论文中的“{hint}”具体讲了什么？请解释关键公式、变量或组成及其作用，并给出论文依据。"
        row["goldSkills"] = ["paper-evidence"]
        set_rationale(row, NO_PROFILE, NEEDS_EVIDENCE, NO_ACTION)
    elif number == 6:
        row["question"] = f"论文是否支持“{hint}”？请先回答支持或不支持，再给出原文中的成立条件、适用范围和限制。"
        row["goldSkills"] = ["paper-evidence"]
        set_rationale(row, NO_PROFILE, NEEDS_EVIDENCE, NO_ACTION)
    elif number == 7:
        row["caseType"] = "rewrite"
        row["context"] = {
            "profileAvailable": False,
            "profileAlreadyLoaded": False,
            "priorEvidenceRead": False,
            "trustedSourceObjectIds": [],
            "selection": None,
        }
        row["question"] = (
            f"下面这句话只是待润色的用户草稿，不要求核对论文："
            f"『{topic}的研究需要同时说明方法、结果和适用范围。』请改写成一句更简洁的中文。"
        )
        row["goldSkills"] = []
        row["evidenceHint"] = "仅改写用户明确提供的文字"
        set_rationale(row, "用户只要求改写已给文本，不需要论文画像。", NO_EVIDENCE, NO_ACTION)
    elif number == 8:
        row["question"] = "请把当前选区高亮为黄色。"
        row["goldSkills"] = ["paper-action"]
        row["executionMode"] = "browser-selection"
        row["context"]["requiresLiveSelection"] = True
        row["evidenceHint"] = "由浏览器注入当前真实选区"
        set_rationale(
            row,
            NO_PROFILE,
            "操作目标由浏览器当前真实选区提供，不需要再次检索论文。",
            "用户明确要求高亮当前选区。",
        )
    elif number == 9:
        target = hint.removeprefix("先取得真实 sourceObjectId，再执行页面操作").strip("；; ")
        if not target:
            selection = row.get("context", {}).get("selection") or {}
            target = selection.get("text") or topic
        # Existing case 9 prompts contain the paper-specific target inside Chinese quotes.
        old_question = row.get("question", "")
        if "找到“" in old_question:
            target = old_question.split("找到“", 1)[1].split("”", 1)[0]
        row["question"] = f"请在当前论文中找到“{target}”，并把对应原文高亮为黄色。"
        row["goldSkills"] = ["paper-evidence", "paper-action"]
        set_rationale(
            row,
            NO_PROFILE,
            "页面操作目标还没有可信来源，需要先检索并定位论文原文。",
            "用户明确要求高亮定位到的原文。",
        )
    elif number == 10 and row["caseType"] == "ambiguous-action":
        row["question"] = "请高亮论文中的关键结论。"
        row["goldSkills"] = ["paper-action"]
        row["evidenceHint"] = "目标不够具体，操作 Skill 应先询问高亮哪一段"
        set_rationale(
            row,
            NO_PROFILE,
            NO_EVIDENCE,
            "用户明确提出高亮操作；目标不够具体时由操作 Skill 发起澄清。",
        )
    elif number == 10 and row["caseType"] == "compound":
        parts = [part.strip() for part in hint.replace("；", ";").split(";") if part.strip()]
        target = parts[-1] if parts else topic
        row["question"] = f"请概括《{topic}》的核心结论，并找到与“{target}”相关的关键原文进行黄色高亮。"
        if row["caseId"] == "paper-09-case-10":
            row["goldSkills"] = ["paper-evidence", "paper-action"]
            profile_rationale = "问题的全文概括可直接结合证据完成，画像可作为导航辅助但非必需。"
        else:
            row["goldSkills"] = ["paper-profile", "paper-evidence", "paper-action"]
            profile_rationale = "问题要求概括全文核心结论，当前没有可复用画像。"
        set_rationale(
            row,
            profile_rationale,
            "回答和高亮目标都依赖当前论文中的真实证据。",
            "用户明确要求高亮找到的关键原文。",
        )
    elif number == 10:
        row["goldSkills"] = []
        set_rationale(row, "问题明确不依赖当前论文，不需要论文画像。", NO_EVIDENCE, NO_ACTION)

    row["requiredSkills"] = list(row["goldSkills"])
    if number == 1:
        row["allowedSkills"] = ["paper-profile", "paper-evidence"]
    elif number == 3:
        row["allowedSkills"] = ["paper-evidence", "paper-profile"]
    elif number in (2, 4, 5, 6):
        row["allowedSkills"] = ["paper-evidence", "paper-profile"]
    elif number == 7:
        row["allowedSkills"] = []
    elif number == 8:
        row["allowedSkills"] = ["paper-action"]
    elif number == 9:
        row["allowedSkills"] = ["paper-evidence", "paper-action", "paper-profile"]
    elif number == 10 and row["caseType"] == "ambiguous-action":
        row["allowedSkills"] = ["paper-action", "paper-profile", "paper-evidence"]
    elif number == 10 and row["caseType"] == "compound":
        row["allowedSkills"] = ["paper-profile", "paper-evidence", "paper-action"]
    else:
        row["allowedSkills"] = []
    return row


def main() -> None:
    rows = [json.loads(line) for line in DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    normalized = [normalize(row) for row in rows]
    DATASET.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in normalized) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
