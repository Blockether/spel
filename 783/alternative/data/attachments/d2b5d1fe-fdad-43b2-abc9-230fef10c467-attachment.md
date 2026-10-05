## PUT http://localhost:45289/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Mon, 05 Oct 2026 21:39:45 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:45289/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
