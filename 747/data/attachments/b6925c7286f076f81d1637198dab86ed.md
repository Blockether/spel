## DELETE http://localhost:44341/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 11 Sep 2026 15:01:06 GMT
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
curl 'http://localhost:44341/echo' \
  -X DELETE
```
