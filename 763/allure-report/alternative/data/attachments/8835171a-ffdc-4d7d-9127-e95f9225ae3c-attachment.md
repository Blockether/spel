## DELETE http://localhost:39567/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 24 Sep 2026 23:50:51 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:39567/echo' \
  -X DELETE
```
