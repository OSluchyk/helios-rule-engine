#!/usr/bin/env python3
"""
Generate test JSONL file with 3000 rules for import testing.
Includes:
- Multiple rule families
- Duplicate rules (to test conflict detection)
- Invalid rules (missing fields, bad values)
- Various operators and conditions
"""

import json
import random
from datetime import datetime, timedelta

# Rule families
FAMILIES = [
    "fraud_detection",
    "customer_segmentation",
    "pricing_engine",
    "marketing_automation",
    "risk_assessment",
    "compliance_check",
    "payment_processing",
    "inventory_management",
    "order_fulfillment",
    "user_authentication"
]

# Field names by family
FAMILY_FIELDS = {
    "fraud_detection": ["transaction_amount", "country", "device_fingerprint", "ip_address", "transaction_count_24h"],
    "customer_segmentation": ["customer_tier", "lifetime_value", "account_age_days", "purchase_frequency", "avg_order_value"],
    "pricing_engine": ["product_category", "quantity", "customer_segment", "season", "competitor_price"],
    "marketing_automation": ["email_open_rate", "click_through_rate", "campaign_type", "customer_engagement_score"],
    "risk_assessment": ["credit_score", "debt_to_income_ratio", "employment_status", "payment_history_score"],
    "compliance_check": ["jurisdiction", "document_type", "verification_status", "kyc_level"],
    "payment_processing": ["payment_method", "amount", "currency", "merchant_category", "card_type"],
    "inventory_management": ["stock_level", "reorder_point", "product_category", "warehouse_location"],
    "order_fulfillment": ["order_status", "shipping_method", "delivery_priority", "package_weight"],
    "user_authentication": ["login_attempts", "last_login_days_ago", "mfa_enabled", "account_status"]
}

# Operators - Only use operators supported by the evaluator
VECTORIZABLE_OPERATORS = ["EQUAL_TO", "NOT_EQUAL_TO", "IS_ANY_OF", "IS_NONE_OF"]
NON_VECTORIZABLE_OPERATORS = ["GREATER_THAN", "LESS_THAN", "BETWEEN"]
ALL_OPERATORS = VECTORIZABLE_OPERATORS + NON_VECTORIZABLE_OPERATORS

# Sample values by field type
SAMPLE_VALUES = {
    "country": ["US", "UK", "CA", "DE", "FR", "JP", "AU"],
    "status": ["ACTIVE", "INACTIVE", "PENDING", "SUSPENDED"],
    "tier": ["BRONZE", "SILVER", "GOLD", "PLATINUM"],
    "category": ["ELECTRONICS", "CLOTHING", "FOOD", "BOOKS", "SPORTS"],
    "method": ["CREDIT_CARD", "DEBIT_CARD", "PAYPAL", "WIRE_TRANSFER"],
    "boolean": [True, False]
}

def generate_rule(rule_num, family, is_duplicate=False, is_invalid=False):
    """Generate a single rule"""

    # Base rule code
    if is_duplicate:
        # Create duplicate of earlier rule
        duplicate_num = max(1, rule_num - random.randint(100, 500))
        rule_code = f"{family}.rule_{duplicate_num:04d}"
    else:
        rule_code = f"{family}.rule_{rule_num:04d}"

    # Generate conditions
    fields = FAMILY_FIELDS[family]
    num_conditions = random.randint(1, 4)
    conditions = []

    for _ in range(num_conditions):
        field = random.choice(fields)
        operator = random.choice(ALL_OPERATORS)

        # Generate appropriate value based on operator
        if operator in ["IS_ANY_OF", "IS_NONE_OF"]:
            value = random.sample(SAMPLE_VALUES.get("country", ["US", "UK", "CA"]), k=random.randint(2, 4))
        elif operator == "BETWEEN":
            # BETWEEN requires [min, max] array
            min_val = random.randint(100, 5000)
            max_val = min_val + random.randint(1000, 5000)
            value = [min_val, max_val]
        elif "THAN" in operator:
            value = random.randint(100, 10000)
        elif field.endswith("_enabled") or field.endswith("mfa_enabled"):
            value = random.choice([True, False])
        else:
            value = random.choice(SAMPLE_VALUES.get("status", ["ACTIVE", "PENDING"]))

        conditions.append({
            "field": field,
            "operator": operator,
            "value": value
        })

    # Base rule structure
    rule = {
        "rule_code": rule_code,
        "description": f"{family.replace('_', ' ').title()} Rule {rule_num}",
        "conditions": conditions,
        "priority": random.randint(1, 1000),
        "enabled": random.choice([True, True, True, False]),  # 75% enabled
        "tags": [family, f"batch_{rule_num // 100}"]
    }

    # Add some rules with additional tags
    if rule_num % 10 == 0:
        rule["tags"].extend(["production-critical", "high-priority"])

    if rule_num % 7 == 0:
        rule["tags"].append("vectorized")

    # Introduce invalid rules
    if is_invalid:
        invalid_type = random.choice([
            "missing_rule_code",
            "missing_description",
            "missing_conditions",
            "empty_conditions",
            "invalid_priority_high",
            "invalid_priority_low",
            "invalid_priority_negative"
        ])

        if invalid_type == "missing_rule_code":
            del rule["rule_code"]
        elif invalid_type == "missing_description":
            del rule["description"]
        elif invalid_type == "missing_conditions":
            del rule["conditions"]
        elif invalid_type == "empty_conditions":
            rule["conditions"] = []
        elif invalid_type == "invalid_priority_high":
            rule["priority"] = 1500  # Over 1000
        elif invalid_type == "invalid_priority_low":
            rule["priority"] = -10  # Negative
        elif invalid_type == "invalid_priority_negative":
            rule["priority"] = -100

    return rule

def generate_test_file(filename, total_rules=3000):
    """Generate JSONL file with test rules"""

    print(f"Generating {total_rules} test rules...")

    # Calculate distribution
    num_duplicates = int(total_rules * 0.05)  # 5% duplicates
    num_invalid = int(total_rules * 0.03)      # 3% invalid
    num_valid = total_rules - num_duplicates - num_invalid

    rules = []
    rule_num = 1

    # Generate valid rules (distributed across families)
    print(f"Generating {num_valid} valid rules...")
    for i in range(num_valid):
        family = FAMILIES[i % len(FAMILIES)]
        rule = generate_rule(rule_num, family, is_duplicate=False, is_invalid=False)
        rules.append(rule)
        rule_num += 1

    # Generate duplicate rules
    print(f"Generating {num_duplicates} duplicate rules...")
    for i in range(num_duplicates):
        family = FAMILIES[i % len(FAMILIES)]
        rule = generate_rule(rule_num, family, is_duplicate=True, is_invalid=False)
        rules.append(rule)
        rule_num += 1

    # Generate invalid rules
    print(f"Generating {num_invalid} invalid rules...")
    for i in range(num_invalid):
        family = FAMILIES[i % len(FAMILIES)]
        rule = generate_rule(rule_num, family, is_duplicate=False, is_invalid=True)
        rules.append(rule)
        rule_num += 1

    # Shuffle rules to mix valid, duplicate, and invalid
    random.shuffle(rules)

    # Write to JSONL file
    print(f"Writing to {filename}...")
    with open(filename, 'w') as f:
        for rule in rules:
            f.write(json.dumps(rule) + '\n')

    # Generate statistics
    stats = {
        "total_rules": total_rules,
        "valid_rules": num_valid,
        "duplicate_rules": num_duplicates,
        "invalid_rules": num_invalid,
        "families": {}
    }

    for family in FAMILIES:
        count = sum(1 for r in rules if r.get("rule_code", "").startswith(family))
        stats["families"][family] = count

    # Write stats file
    stats_filename = filename.replace('.jsonl', '_stats.json')
    with open(stats_filename, 'w') as f:
        f.write(json.dumps(stats, indent=2))

    print(f"\n✅ Generated {total_rules} rules")
    print(f"   - Valid: {num_valid} ({num_valid/total_rules*100:.1f}%)")
    print(f"   - Duplicates: {num_duplicates} ({num_duplicates/total_rules*100:.1f}%)")
    print(f"   - Invalid: {num_invalid} ({num_invalid/total_rules*100:.1f}%)")
    print(f"\n📊 Rules by family:")
    for family, count in stats["families"].items():
        print(f"   - {family}: {count}")
    print(f"\n📁 Files created:")
    print(f"   - {filename}")
    print(f"   - {stats_filename}")

if __name__ == "__main__":
    random.seed(42)  # For reproducibility
    generate_test_file("rules/test-rules-3k.jsonl", total_rules=3000)
