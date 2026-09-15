## DELETE http://localhost:41219/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 15 Sep 2026 11:16:50 GMT
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
curl 'http://localhost:41219/echo' \
  -X DELETE
```
