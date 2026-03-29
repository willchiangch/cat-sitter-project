import re
import os

files_to_process = [
    "backend/src/main/java/com/catsitter/api/entity/SubscriptionPlan.java",
    "backend/src/main/java/com/catsitter/api/entity/PaymentTransaction.java",
    "backend/src/main/java/com/catsitter/api/entity/PromoCode.java",
    "backend/src/main/java/com/catsitter/api/entity/SitterSubscription.java",
]

def capitalize(s):
    return s[0].upper() + s[1:]

for filepath in files_to_process:
    with open(filepath, 'r') as f:
        content = f.read()

    if '@Getter' not in content:
        continue

    # Remove lombok imports
    content = re.sub(r'import lombok\..*;\n', '', content)
    content = re.sub(r'@Getter\n', '', content)
    content = re.sub(r'@Setter\n', '', content)

    # Extract class body
    match = re.search(r'public class (\w+) extends \w+ \{([\s\S]*)\}', content)
    if not match:
        continue
    
    class_name = match.group(1)
    body = match.group(2)
    
    fields = re.findall(r'private +([\w<>\[\]\.]+) +(\w+) *(?:=.*?)?;', body)
    
    methods = "\n"
    for type_name, var_name in fields:
        methods += f"    public {type_name} get{capitalize(var_name)}() {{ return this.{var_name}; }}\n"
        methods += f"    public void set{capitalize(var_name)}({type_name} {var_name}) {{ this.{var_name} = {var_name}; }}\n"
    
    # Insert before the last closing brace
    content = content.replace(body, body.rstrip() + "\n" + methods + "}\n")
    # Remove the extra brace added by the replace
    content = content[:-4] + "}\n"
    
    with open(filepath, 'w') as f:
        f.write(content)
    print(f"Processed {filepath}")

# Fix slf4j files
slf_files = [
    "backend/src/main/java/com/catsitter/api/controller/v1/StorageController.java",
    "backend/src/main/java/com/catsitter/api/service/storage/GcsStorageService.java"
]

for filepath in slf_files:
    with open(filepath, 'r') as f:
        content = f.read()
    
    if '@Slf4j' not in content:
        continue

    content = re.sub(r'import lombok.extern.slf4j.Slf4j;\n', 'import org.slf4j.Logger;\nimport org.slf4j.LoggerFactory;\n', content)
    content = re.sub(r'@Slf4j\n', '', content)
    
    class_name = re.search(r'public class (\w+)', content).group(1)
    logger_field = f"\n    private static final Logger log = LoggerFactory.getLogger({class_name}.class);\n"
    
    # insert after class declaration
    content = re.sub(f'public class {class_name}[^{{]*{{', lambda m: m.group(0) + logger_field, content)
    
    with open(filepath, 'w') as f:
        f.write(content)
    print(f"Processed {filepath}")
