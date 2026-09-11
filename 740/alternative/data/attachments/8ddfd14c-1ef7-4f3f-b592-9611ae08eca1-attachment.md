## PUT http://localhost:39675/echo → 200 OK

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
date: Fri, 11 Sep 2026 03:35:53 GMT
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
curl 'http://localhost:39675/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
