## PATCH http://localhost:41147/echo → 200 OK

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
date: Mon, 05 Oct 2026 16:36:49 GMT
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
curl 'http://localhost:41147/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
