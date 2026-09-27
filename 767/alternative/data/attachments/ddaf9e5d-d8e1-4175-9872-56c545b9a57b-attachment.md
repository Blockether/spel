## PATCH http://localhost:37159/echo → 200 OK

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
date: Sun, 27 Sep 2026 17:53:58 GMT
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
curl 'http://localhost:37159/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
