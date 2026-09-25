## PATCH http://localhost:42631/echo → 200 OK

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
date: Thu, 24 Sep 2026 23:50:47 GMT
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
curl 'http://localhost:42631/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
