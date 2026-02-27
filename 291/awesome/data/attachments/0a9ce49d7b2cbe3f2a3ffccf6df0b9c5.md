## PATCH /echo → 200 OK

### Request Headers
```
PATCH /echo
```

### Request Body
```json
{
  "email": "alice3@example.com"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Fri, 27 Feb 2026 09:50:42 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.com"
  }
}
```

### cURL
```bash
curl '/echo' \
  -X PATCH \
  -d '{"email":"alice3@example.com"}'
```
