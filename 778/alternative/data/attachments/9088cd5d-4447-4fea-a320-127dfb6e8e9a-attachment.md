## PATCH http://localhost:42969/echo → 200 OK

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Sat, 03 Oct 2026 17:10:28 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:42969/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
