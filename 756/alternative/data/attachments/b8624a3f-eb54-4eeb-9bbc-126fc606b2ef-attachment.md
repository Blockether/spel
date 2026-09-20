## PATCH http://localhost:35345/echo → 200 OK

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
date: Sun, 20 Sep 2026 13:27:32 GMT
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
curl 'http://localhost:35345/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
