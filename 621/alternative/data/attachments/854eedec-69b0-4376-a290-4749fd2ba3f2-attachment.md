## PATCH http://localhost:37239/echo → 200 OK

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
date: Wed, 22 Apr 2026 17:51:21 GMT
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
curl 'http://localhost:37239/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
