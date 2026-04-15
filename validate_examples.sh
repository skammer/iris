#!/bin/bash
# Script to validate example files structure

echo "=== Clojure AI Agent Examples Validation ==="
echo "Date: $(date)"
echo ""

EXAMPLES_DIR="/home/skammer/projects/clj-agent/examples"
TEST_DIR="/home/skammer/projects/clj-agent/test/examples"

echo "1. Checking example files..."
for file in "$EXAMPLES_DIR"/*.clj; do
    if [ -f "$file" ]; then
        filename=$(basename "$file")
        echo "  ✓ $filename"
        
        # Check file structure
        if grep -q "ns examples\." "$file"; then
            echo "    - Has proper namespace declaration"
        else
            echo "    - WARNING: Missing proper namespace"
        fi
        
        # Check for main function or demo function
        if grep -q "defn.*-demo\|defn.*main\|defn.*run" "$file"; then
            echo "    - Contains demo/main function"
        fi
        
        # Count lines
        lines=$(wc -l < "$file")
        echo "    - Lines: $lines"
    fi
done

echo ""
echo "2. Checking test files..."
for file in "$TEST_DIR"/*.clj; do
    if [ -f "$file" ]; then
        filename=$(basename "$file")
        echo "  ✓ $filename"
    fi
done

echo ""
echo "3. Summary:"
echo "   Example files: $(ls -1 "$EXAMPLES_DIR"/*.clj 2>/dev/null | wc -l)"
echo "   Test files: $(ls -1 "$TEST_DIR"/*.clj 2>/dev/null | wc -l)"

echo ""
echo "=== Validation Complete ==="