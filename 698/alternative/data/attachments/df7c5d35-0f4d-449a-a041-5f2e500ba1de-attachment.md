## PATCH http://localhost:37545/echo → 200 OK

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
date: Sun, 16 Aug 2026 02:40:12 GMT
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
curl 'http://localhost:37545/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
