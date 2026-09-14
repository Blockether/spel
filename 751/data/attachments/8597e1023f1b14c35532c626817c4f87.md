## PATCH http://localhost:32963/echo → 200 OK

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
date: Mon, 14 Sep 2026 12:40:43 GMT
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
curl 'http://localhost:32963/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
