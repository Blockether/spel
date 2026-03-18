## POST /echo → 200 OK

### Request Headers
```
POST /echo
```

### Request Body
```json
{
  "name": "Alice",
  "email": "alice@example.org"
}
```

### Response Headers
```
content-length: 84
content-type: application/json
date: Wed, 18 Mar 2026 05:51:26 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Alice",
    "email": "alice@example.org"
  }
}
```

### cURL
```bash
curl '/echo' \
  -X POST \
  -d '{"name":"Alice","email":"alice@example.org"}'
```
