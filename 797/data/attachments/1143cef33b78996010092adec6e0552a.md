## PATCH http://localhost:33411/echo → 200 OK

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
date: Sat, 24 Oct 2026 08:08:27 GMT
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
curl 'http://localhost:33411/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
